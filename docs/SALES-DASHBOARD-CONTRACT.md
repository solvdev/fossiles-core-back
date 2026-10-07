# Contrato — Dashboard de ventas por fuente

Base: `/api/sales`. Todos los endpoints son `GET`, JSON, autenticados (igual que el resto de `/api/sales`).
Fechas `yyyy-MM-dd`. Dinero en quetzales con 2 decimales (`number`). `growthPercent` es un **porcentaje** ya escalado (12.4 = +12.4 %), no decimal.

Parámetros comunes: `startDate`, `endDate` (default: 1 del mes actual → hoy), `refresh=true` (omite/invalida la caché de esa clave).
Rango inválido (`startDate > endDate`) → `BusinessException` (400) con el mensaje actual.
Periodo anterior = mismo número de días inmediatamente antes de `startDate`.

## Reglas
- **Dinero** (`totalAmount`) incluye empaque y envío (igual que el dashboard anterior): Kiosko = `KioskSale.totalAmount`; Online = `OnlineSale.totalAmount` (incluye envío); Vendedor LF = estimado (ítems + empaque + envío) igual que `CustomerAccountService.estimateVendorOrderTotal`.
- Siempre: `productAmount + packagingAmount + shippingAmount == totalAmount`.
  - Kiosko: `packagingAmount = Σ lineTotal` de ítems cuyo código es empaque; `productAmount = totalAmount − packagingAmount`; `shippingAmount = 0`.
  - Online: `shippingAmount = shippingCost`; `packagingAmount = Σ subtotal` de ítems empaque; `productAmount = totalAmount − shippingAmount − packagingAmount`.
  - Vendedor LF: `packagingAmount = packing (observations __OPV_PACKING__) + Σ subtotal de ítems cuyo producto es empaque`; `shippingAmount = __OPV_SHIPPING__`; `productAmount = Σ subtotal de ítems no empaque`.
- **Empaque** = código de producto que empieza con `SUM` (`ProductCinchoType.isPackagingProductCode`). Encapsular en `FinishedProductClassifier`. Ítem sin código y sin productId resoluble → producto terminado.
- **Unidades / rankings de producto** solo cuentan producto terminado. Agrupar por `productId` (si es null, por `productCode`, si no por nombre normalizado). El nombre mostrado = nombre del producto base (sin sufijo " T.xx" de talla).
- Ventas válidas (idénticas a las del `SalesDashboardService` anterior):
  - Kiosko: `KioskPosService.countsForProductionMetrics` y status ∉ {CANCELLED, VOID}.
  - Online: status ∉ {CANCELADO, CANCELADA, ANULADA}.
  - Vendedor LF: `CustomerAccountService.isLfVendorOrder`, `vendorShipmentVoidedAt == null`, fecha = `startDate` de la orden o `createdAt` si no hay.
- Tendencia mensual: 6 meses calendario terminando en el mes de `endDate`, independiente de `startDate`.
- Serie diaria: un punto por cada día del rango (días sin venta con `amount: 0, count: 0`).

## Tipos compartidos
```
SourceKpis {
  totalAmount, productAmount, packagingAmount, shippingAmount,
  previousTotalAmount, growthPercent,
  dailyAmount,            // total del día de HOY (si hoy cae fuera del rango: 0)
  salesCount,             // int: tickets / pedidos / órdenes
  unitsFinished,          // number: unidades de producto terminado
  avgTicket               // totalAmount / salesCount (0 si salesCount = 0)
}
DailyPoint   { date, amount, count }
TrendPoint   { label, year, month, amount }          // label: "sep" (es-GT, 3 letras)
ProductRank  { productId, productCode, productName, units, amount }   // amount = venta de producto de ese producto
BreakdownRow { key, label, count, amount, sharePercent }              // sharePercent sobre totalAmount de la fuente
SaleRow      { id, saleDate, reference, productLabel, quantity, totalAmount, status, party }
             // party = kiosko (Kiosko), vendedora (Online) o cliente (Vendedor LF); productLabel = "Billetera +2 más" solo con terminados (si solo hay empaques: "Solo empaque")
```

## `GET /api/sales/dashboard/consolidated`
```
{
  startDate, endDate, previousStartDate, previousEndDate,
  totals: SourceKpis,                         // suma de las 3 fuentes (unitsFinished suma unidades)
  sources: [ { channel: "KIOSKO"|"ONLINE"|"VENDOR", label: "Kioskos"|"Online"|"Vendedor LF",
               kpis: SourceKpis, sharePercent } ],     // siempre 3, en ese orden
  monthlyTrend: [ { label, year, month, kiosko, online, vendor, total } ],   // 6 puntos
  dailySeries:  [ { date, kiosko, online, vendor, total } ]
}
```
Sin rankings de producto mezclados.

## `GET /api/sales/dashboard/kiosks` (+ `kioskLocationId` opcional)
## `GET /api/sales/dashboard/online`
## `GET /api/sales/dashboard/vendor`
Mismo sobre (`SalesSourceDetailResponse`):
```
{
  channel, label, startDate, endDate, previousStartDate, previousEndDate,
  kpis: SourceKpis,
  dailySeries: [DailyPoint], monthlyTrend: [TrendPoint] (6),
  topProducts: [ProductRank]   // top 10 por unidades, solo terminados
  recentSales: [SaleRow]       // 20 más recientes
  breakdowns: { <clave>: [BreakdownRow] },   // ordenado por amount desc
  kioskOptions: [ { kioskId, kioskCode, kioskName } ] | null   // solo /kiosks; TODOS los kioscos con venta en el rango, sin aplicar kioskLocationId
}
```
Claves de `breakdowns`:
- kiosks: `byKiosk` (key = kioskId; respeta kioskLocationId → 1 fila si filtrado), `byPaymentMethod`
- online: `bySeller`, `bySocialNetwork`, `byPaymentMethod`, `byStatus`
- vendor: `byCustomer`, `byOrderType` (key `OPV` | `OPC`, label "OPV Fossiles" | "OPC marcas y cinchos"; usa la misma clasificación de `CustomerAccountService.classifyOrderKind`), `byStatus`

Valores nulos de etiqueta → `"Sin dato"`.

## `GET /api/sales/unified` (existente)
Misma firma y respuesta (`UnifiedSaleRow`). Solo se elimina el N+1 interno.

## `GET /api/sales/opv-shipments` (existente)
Sin cambios. El `GET /api/sales/dashboard` antiguo se elimina.

## Caché
Caffeine, TTL 60 s, máx. 300 entradas, clave `(fuente, startDate, endDate, kioskLocationId)`. `refresh=true` evicta la clave y recalcula.
