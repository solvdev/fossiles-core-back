# Cuentas por cobrar: el cargo nace al despachar el envío

## Regla

- **Un cargo (`CHARGE`) por envío**, creado automáticamente cuando el envío pasa a **SENT** (`ProductDistributionService.sendShipment`).
- El monto es lo **realmente enviado**: `product_shipment_detail.quantity × unit_price` (precio congelado de la línea). No incluye empaque.
- Si el envío tiene `shipping_cost`, se crea en el mismo paso un `CHARGE_ADJUSTMENT` (ajuste de envío) ligado a ese cargo.
- **Despachado = SENT o DELIVERED.** Un envío en borrador, confirmado o cancelado no genera cargo ni aparece en la cartera.
- El saldo del cliente es la suma de los cargos de sus envíos despachados, menos pagos, notas de crédito y devoluciones.
- Solo órdenes de cartera LF (`isLfReceivableOrder`), igual que antes.

## Qué pasa cuando el envío cambia

| Acción | Efecto en el libro |
|---|---|
| Despachar (SENT) | Crea el cargo (y el ajuste de envío). Idempotente. |
| Editar productos / costo de envío de un envío despachado | Recalcula el cargo y el ajuste. Se rechaza si los pagos ya aplicados superarían el nuevo total. |
| Revertir a bodega (SENT → CONFIRMED) o cancelar | Anula (`VOID`) el cargo y el ajuste. Se rechaza si el cargo ya tiene pagos, notas de crédito o devoluciones: hay que anularlos o trasladarlos antes. |

El despacho físico nunca se bloquea por el cargo: si no corresponde cargo (orden fuera de cartera LF, sin productos con precio) el envío sale igual y queda en cartera como **despachado sin cargo** (estado `NONE`).

## Reintento manual

`POST /api/customer-accounts/customers/{id}/entries` con `entryType=CHARGE`, `productionOrderId` y `productShipmentId` crea el cargo de un envío **ya despachado** que quedó sin cargo. El monto siempre lo calcula el servidor (`GET /api/customer-accounts/shipments/{id}/charge-quote`). El cargo por orden completa ya no se puede crear.

## Cargos anteriores (por orden completa)

Los cargos `CHARGE` sin envío (`product_shipment_id IS NULL`) **no se modifican en el libro**. Al leer (`CustomerAccountService.visibleEntries`) se recortan a lo despachado de esa orden: `min(monto del cargo, valor de los productos de los envíos SENT/DELIVERED)`. Lo que no ha salido no se muestra como pendiente de pago ni en pantalla ni en los reportes descargables (resumen, estado de cuenta, cartera RUTAS CxC).

- Una orden con un cargo por orden completa **no recibe** cargos por envío (evita cobrar dos veces).
- Un cargo por orden completa cuya orden no tiene ningún envío despachado queda en cero y no se lista.
- Los ajustes de envío ligados a un envío no despachado no se cuentan.

## Despliegue

1. Correr `scripts/migration-customer-account-shipment-charge.sql` en `fosstest` y luego en `fossilesgt`, **antes** de desplegar el backend (cambia el índice único de cargos activos: de uno por orden a uno por envío; el cargo por orden completa anterior sigue siendo único por orden).
2. Desplegar backend y frontend.
3. Opcional: `scripts/audit-customer-account-dispatch-charges.sql` (solo lectura) lista envíos despachados sin cargo, cargos por orden completa con envíos sin despachar y cargos ligados a envíos no despachados.

Rollback del esquema: `scripts/rollback-customer-account-shipment-charge.sql` (solo si ninguna orden tiene más de un cargo activo).

No hay backfill automático: los envíos despachados antes del cambio no generan cargo solos.

## Archivos

- `CustomerAccountService`: `createChargeForDispatchedShipment`, `voidEntriesForShipment`, `syncShipmentEntries`, `quoteShipmentCharge`, `visibleEntries`.
- `ProductDistributionService`: ganchos en `sendShipment`, `cancelShipment`, `revertSentShipment`, `updateShipmentProducts`, `updateShipmentShippingCost`.
- `CustomerAccountPortfolioReportService` y `OpvShipmentCatalogService`: leen los movimientos con `visibleEntries`.
