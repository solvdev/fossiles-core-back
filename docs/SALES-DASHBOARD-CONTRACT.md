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

---

# Addendum — Inversión en publicidad vs venta online (por día)

Base: `/api/sales/online/ad-spend`. Un solo monto por día (gasto general de todas las plataformas). Se compara contra la **venta total online del día** (`OnlineSale.totalAmount`, con envío, mismas ventas válidas del dashboard: sin CANCELADO/CANCELADA/ANULADA; fecha = `saleDate`).
Resultado del día = `salesAmount − adSpend` (quetzales) y `roas = salesAmount / adSpend` (Q vendidos por cada Q1 invertido). No incluye costo de producción.
Tabla nueva `online_ad_spend` (migración manual `scripts/migration-online-ad-spend.sql`; el proyecto usa `ddl-auto=validate`, hay que correrla ANTES de desplegar el backend).

## Tipos
```
AdSpendEntry { date, amount, notes, updatedAt, updatedBy }
```
Reglas de validación (error 400 `BusinessException`, mensajes en español): `amount` ≥ 0, máx. 9,999,999.99, 2 decimales; `date` no puede ser posterior a hoy (hora Guatemala); `notes` ≤ 255 caracteres.

## Endpoints
- `GET  /api/sales/online/ad-spend?startDate&endDate` → `[AdSpendEntry]` (ordenado por fecha asc).
- `PUT  /api/sales/online/ad-spend/{date}` body `{ "amount": 1500.00, "notes": "..." }` → `AdSpendEntry` (crea o actualiza ese día).
- `DELETE /api/sales/online/ad-spend/{date}` → 204 (borra la captura de ese día; si no existe, 204 igual).
- `POST /api/sales/online/ad-spend/bulk` body `{ "entries": [ { "date": "2026-09-03", "amount": 1500.00, "notes": null }, { "date": "2026-09-04", "amount": null } ] }`
  `amount: null` = borrar la captura de ese día. Máx. 400 entradas, fechas únicas, todo-o-nada (transaccional). → `{ "saved": n, "deleted": m }`.
- `GET  /api/sales/online/ad-spend/report?startDate&endDate` (default: 1 del mes → hoy; máx. 400 días, si no 400 con mensaje):
```
{
  startDate, endDate,
  totals: {
    salesAmount,            // venta total online de TODO el rango
    ordersCount,
    comparableSales,        // venta de los días con inversión capturada
    adSpend,                // Σ inversión capturada
    netResult,              // comparableSales − adSpend
    roas,                   // comparableSales / adSpend; null si adSpend = 0
    daysWithSpend, daysNoSpend,   // días con / sin captura (adSpend ausente)
    daysWin, daysLoss, daysEven
  },
  days: [ {                 // un elemento por cada día del rango, en orden asc
    date, salesAmount, ordersCount,
    adSpend,                // null si no hay captura
    netResult,              // null si adSpend null
    roas,                   // null si adSpend null o 0
    status,                 // "WIN" (net>0) | "LOSS" (net<0) | "EVEN" (net=0) | "NO_SPEND" (sin captura)
    notes
  } ]
}
```
El reporte NO se cachea. Guardar/borrar inversión no necesita invalidar la caché del dashboard (no la usa).
Permisos: seguir el mismo patrón de autorización que las operaciones de escritura de ventas online (ver `OnlineSaleController`/seguridad); ver el reporte requiere el mismo acceso que ver el dashboard de ventas.
