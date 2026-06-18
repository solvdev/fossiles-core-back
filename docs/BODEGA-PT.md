# Bodega de producto terminado (PT)

Vista web: `/admin/warehouse-view` — recepción y despacho **por pieza física** (color/talla).

## Modelo

Tabla `production_order_warehouse_unit`: una fila por unidad planificada (`PENDING` | `RECEIVED` | `REJECTED`), con referencia de envío al despachar (`ONLINE_SALE` o `PRODUCT_SHIPMENT`).

Las unidades se generan al abrir el workspace (desde `quantity` o `sizes_data` en cinchos). `warehouse_received_qty` en el ítem se sincroniza al recibir piezas.

`production_order.warehouse_receipt_closed_at` marca el cierre operativo en bodega (distinto de `status=COMPLETED` de producción).

## API

| Método | Ruta | Uso |
|--------|------|-----|
| GET | `/api/production-orders/warehouse-view` | Listado de OP (existente) |
| GET | `/api/production-orders/{id}/warehouse-workspace` | OP + piezas + resumen + envíos |
| PUT | `/api/production-orders/{id}/warehouse-units/receipt` | Batch `{ units: [{ unitId, receiptStatus, rejectionReason? }] }` |
| POST | `/api/production-orders/{id}/warehouse-receipt/close` | Cierra recepción si no hay `PENDING` |
| PUT | `/api/production-orders/{id}/dispatch-customer/{onlineSaleId}` | Despacho venta online (marca piezas PT recibidas si existen; no bloquea por recepción incompleta) |
| PUT | `/api/product-distributions/shipments/{id}/confirm-draft` | Confirma envío (líneas desde OP/parciales); marca piezas PT si existen |

Migración: `scripts/migration-production-order-warehouse-unit.sql`.

**La recepción en bodega cierra tareas en `AWAITING_WAREHOUSE` y define `completedAt` / `actualDurationMinutes`.**

## Cierre de tareas desde bodega PT

Cuando una OP tiene piezas en `production_order_warehouse_unit`:

1. En mesa, **Completar** deja la tarea en **`AWAITING_WAREHOUSE`** (visible en centro de producción como “Pendiente bodega PT”).
2. Al marcar cada pieza **`RECEIVED`** en bodega PT, si ya no quedan piezas pendientes para los ítems de esa tarea, pasa a **`COMPLETED`** con `completedAt = receivedAt`.
3. OPs sin modelo de piezas PT siguen completándose directamente en mesa (comportamiento anterior).

## Flujo operativo

1. **Recepción:** expandir OP → marcar cada pieza recibida o rechazada (motivo obligatorio) → guardar o “recibir todas pendientes”.
2. **Cierre:** cuando no queden piezas `PENDING`, “Cerrar recepción en bodega”.
3. **Despacho:** marcar enviado (app móvil o API) **no exige** que todas las piezas estén recepcionadas en PT; si hay piezas `RECEIVED` sin despachar, se enlazan al envío. Confirmar envío de distribución tampoco bloquea por recepción incompleta.

## Pruebas

`ProductionOrderWarehouseUnitServiceTest` (perfil `test`, H2): generación de unidades, inventario BODEGA_PT, rechazo/reproceso, cierre y despacho online.
