# Kiosco Inventory - Testing Guide

Esta guía valida el módulo nuevo `kiosco-inventory` y su integración con POS/Distribución.

## Prerrequisitos
- Ejecutar migración: `scripts/migration-kiosco-inventory.sql`.
- Reiniciar backend.
- Tener al menos 2 locations de categoría `KIOSKO`.
- Tener productos y colores cargados.

## Escenario 1 - Flujo básico de un kiosko
1. Crear/usar un kiosko de prueba (`KIOSKO`).
2. `POST /api/kiosco-inventory/{locationId}/entrada` con 50 unidades del producto X.
3. `POST /api/kiosco-inventory/{locationId}/venta` con 10 unidades.
4. Verificar `GET /stock`: stock = 40.
5. Registrar otra venta de 35.
6. Verificar stock = 5 y que `GET /stock-bajo` incluya el producto (si mínimo = 10).
7. Intentar venta de 10.
8. Verificar error de stock insuficiente, stock se mantiene en 5 y no se agrega movimiento inválido.

## Escenario 2 - Traslado entre kioskos
1. Kiosko A: dejar stock en 30 del producto X.
2. Kiosko B: dejar stock en 5 del producto X.
3. `POST /api/kiosco-inventory/traslado` moviendo 20 de A a B.
4. Verificar `GET /stock`:
   - A = 10
   - B = 25
5. Verificar en movimientos:
   - existe `TRASLADO_SALIDA` en A
   - existe `TRASLADO_ENTRADA` en B
   - ambos comparten `referenceId`.

## Escenario 3 - Anulación sin salida de producto
1. Registrar venta de 5 (30 -> 25).
2. `POST /api/kiosco-inventory/{locationId}/anular-factura` con `productLeftKiosk=false`.
3. Verificar stock vuelve a 30.
4. Verificar en movimientos:
   - permanece el registro de `VENTA`.
   - se agrega `ANULACION` con referencia de factura.

## Escenario 4 - Anulación con producto fuera del kiosko
1. Registrar venta de 5 (20 -> 15).
2. Anular con `productLeftKiosk=true`.
3. Verificar stock sigue en 15.
4. Registrar devolución cliente con `apto=true`.
5. Verificar stock sube a 20.

## Escenario 5 - Devolución cliente no apto
1. Registrar venta de 3.
2. Registrar devolución cliente con `apto=false`.
3. Verificar stock no cambia.
4. Verificar movimientos generados:
   - `DEVOLUCION_CLIENTE` con `affectsStock=false`
   - `MERMA` con `affectsStock=false` y motivo.

## Escenario 6 - Ajuste de inventario
1. Si sistema indica stock = 40.
2. Registrar ajuste con `realQuantity = 37` y motivo `diferencia de conteo`.
3. Verificar stock = 37.
4. Verificar movimiento `AJUSTE` con `quantity=3`, `stockBefore=40`, `stockAfter=37`.
5. Repetir con `realQuantity = 37` (sin diferencia) y verificar que igualmente se registre un ajuste.

## Escenario 7 - Merma
1. Con stock = 20 registrar merma de 2, motivo `producto vencido`.
2. Verificar stock = 18.
3. Intentar registrar merma sin motivo.
4. Verificar rechazo por validación.

## Escenario 8 - Invariante de stock negativo
1. Con stock = 5 intentar venta de 6.
2. Verificar:
   - operación rechazada.
   - stock sigue en 5.
   - no se crea movimiento de salida para la operación rechazada.

## Verificación de integración automática
- POS:
  - Registrar venta desde `Kiosk POS`.
  - Verificar movimiento `VENTA` en `kiosco_movement`.
  - Anular venta POS y verificar `ANULACION`.
- Distribución:
  - Confirmar recepción (`SENT -> DELIVERED`).
  - Verificar `ENTRADA` en `kiosco_movement`.
- Transferencias:
  - Crear transferencia de producto entre kioskos desde módulo legacy.
  - Verificar `TRASLADO_SALIDA/ENTRADA` en módulo kiosko.
