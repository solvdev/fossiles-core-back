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

---

# Addendum 2 — Ventas de kioscos desde la fuente de Finanzas kioscos (histórico + POS)

Pedido del usuario: las ventas de kioscos del dashboard deben coincidir con **Finanzas kioscos**, que además del POS tiene datos históricos (`kiosk_daily_sales_hist`), y ahí el dinero incluye TODO (empaque incluido).

## Fuente del dinero (canal KIOSKO)
Misma que Finanzas: `KioskSalesSourceResolver.resolve(sites, from, to, goLive)` (HIST antes del go-live efectivo del sitio, POS desde el go-live) sobre los sitios `kiosk_site` con `exclude_from_reports = false` (`KioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()`). Solo se LLAMA al resolver y repos de Finanzas (solo lectura); no se modifica código de Finanzas.
De esa fuente salen, para el canal KIOSKO: `kpis.totalAmount`, `previousTotalAmount`, `growthPercent`, `dailyAmount` (hoy), `dailySeries[].amount`, `monthlyTrend[].amount` y los importes de `breakdowns.byKiosk`. Es el mismo número que muestra Finanzas kioscos para esos días/sitios; incluye empaque.

## Detalle solo-POS (lo único que tiene tickets)
`kpis.salesCount` (tickets), `kpis.unitsFinished`, `kpis.productAmount`, `kpis.packagingAmount`, `kpis.avgTicket`, `dailySeries[].count`, `breakdowns.byPaymentMethod`, `topProducts`, `recentSales` salen SOLO de ventas POS (`KioskSalesDashboardService` actual: cabeceras + ítems en lote) restringidas a: locations ligadas a un sitio incluido y `saleDate >= goLive efectivo de ese sitio` (mismo corte que Finanzas; así lo POS detallado == la parte POS del total de Finanzas). Se cargan solo las cabeceras/ítems del periodo actual (el periodo anterior y la tendencia ya no necesitan POS).
- `kpis.historicalAmount` (NUEVO, BigDecimal, 2 decimales) = `max(0, totalAmount − productAmount − packagingAmount)`: parte del total que viene del histórico (sin tickets ni desglose). Invariante del canal KIOSKO: `productAmount + packagingAmount + shippingAmount(=0) + historicalAmount == totalAmount`.
- `kpis.avgTicket` (KIOSKO) = `(productAmount + packagingAmount) / salesCount` (0 si no hay tickets POS).
- Los otros canales devuelven `historicalAmount = 0.00`.
- Consolidado: `totals.historicalAmount` = suma de las 3 fuentes; `totals.avgTicket` = `(totals.totalAmount − totals.historicalAmount) / totals.salesCount`.

## Filtro por kiosko
`GET /api/sales/dashboard/kiosks?startDate&endDate&siteId&kioskLocationId&refresh`
- `siteId` (NUEVO): id de `kiosk_site`. Filtra dinero y detalle a ese sitio (si el sitio es histórico, sin location, el detalle POS sale vacío y el dinero viene del histórico).
- `kioskLocationId` (legacy, sigue aceptado): se traduce al sitio ligado a esa location (`KioskSiteRepository.findByLocationId`); si no hay sitio incluido → `BusinessException("El kiosko seleccionado no existe o no está incluido en los reportes.")`. Igual error si `siteId` no existe o está excluido. Si llegan ambos, manda `siteId`.
- La tendencia de 6 meses y el periodo anterior respetan el filtro.
- Clave de caché: agrega `siteId` (y `kioskLocationId`).

## byKiosk, byPaymentMethod, kioskOptions
- `breakdowns.byKiosk`: una fila por SITIO con venta ≠ 0 en el periodo: `key = String(siteId)`, `label = site.name`, `amount` = venta del sitio (fuente Finanzas), `count` = tickets POS del sitio en el periodo (0 si es histórico), `sharePercent` sobre `totalAmount`; orden `amount` desc.
- `breakdowns.byPaymentMethod`: solo POS; `sharePercent` sobre `productAmount + packagingAmount` (la base POS), no sobre el total con histórico.
- `kioskOptions[]` ahora: `{ siteId, kioskId, kioskCode, kioskName }` donde `siteId` = id del sitio (valor para el selector y para el parámetro `siteId`), `kioskId` = id de la location POS (null si es histórico), `kioskCode` = código de la location o "", `kioskName` = `site.name`. Lista = sitios con venta ≠ 0 en el periodo (más el `siteId` pedido aunque no tenga venta), SIN aplicar el filtro de kiosko, orden por nombre.

## Respuesta consolidada
`GET /dashboard/consolidated` usa la misma construcción del canal KIOSKO (sin filtro de sitio), por lo que sus `sources[0].kpis`, `monthlyTrend[].kiosko` y `dailySeries[].kiosko` coinciden con la pestaña Kioskos.

## Frontend (comportamiento esperado)
- Pestaña Kioskos: selector de kiosko por `siteId`; barra de composición con 4º segmento "Histórico (sin desglose)" cuando `historicalAmount > 0`; tarjetas "Tickets (POS)", "Unidades terminadas (POS)", "Ticket promedio (POS)"; forma de pago y productos más vendidos rotulados "solo POS"; aviso cuando `historicalAmount > 0`: "Q X vienen del histórico de Finanzas kioscos (sin tickets ni productos)".
- Consolidado: composición y tabla "Producto terminado por fuente" suman la columna/segmento "Histórico" (solo se muestra si `historicalAmount > 0`); ticket promedio con la definición nueva.

---

# Addendum 3 — Mapa de calor de kioscos (insights, tendencias y clasificación A/B/C)

Pedido del usuario: en la pestaña Kioskos, un mapa de calor "como el de Online pero dirigido a los kioscos", con insights de los días de más venta, tendencias entre kioscos y la clasificación del kiosco (A, B o C), que sale de `kiosk_site.sales_category` (manual, la fija Finanzas kioscos; `null` = sin clasificar; `KioskFinancialsConfigService.normalizeSalesCategory` solo admite A, B, C o vacío).

## Endpoint nuevo
`GET /api/sales/dashboard/kiosks/heatmap?startDate&endDate&refresh=false`
- Mismos defaults/validación de rango que el resto (`resolveRange`); máximo 400 días (si no, `BusinessException("El mapa de calor admite un máximo de 400 días.")`).
- NO tiene filtro por kiosko: siempre compara TODOS los sitios incluidos (`exclude_from_reports = false`), porque sirve para encontrar tendencias entre kioscos.
- Misma fuente de dinero que Finanzas (Addendum 2): `KioskSalesSourceResolver.goLiveEffective(sites)` una vez + `resolve(sites, previousStart, endDate, goLive)` una vez (el rango cubre periodo anterior + actual). Incluye empaque. Sin consultas POS de detalle (solo el agregado diario del resolver).
- Caché de 60 s (`SalesDashboardCache`), clave `("KIOSK_HEATMAP", start, end, null, null)`; `refresh=true` la omite.

## Respuesta `KioskHeatmapResponse`
```
{
  startDate, endDate, previousStartDate, previousEndDate,
  days: ["2026-10-01", ...],                 // cada día del rango actual, en orden
  sites: [ {
    siteId, name,
    category,                                // "A" | "B" | "C" | null
    locationId,                              // id de location POS o null (sitio histórico)
    source,                                  // "HIST" | "POS" | "MIXED" | "NONE" (según SiteSales.source(); NONE si no hay datos en el rango)
    total,                                   // Σ en el periodo actual, 2 decimales
    previousTotal,                           // Σ en el periodo anterior
    growthPercent,                           // % ya escalado (12.4 = +12.4 %), mismo criterio que SalesDashboardSupport.growthPercent
    daysWithSales,                           // días con venta > 0 en el periodo actual
    daily: [ number ]                        // un monto por día, alineado índice a índice con `days` (0.00 si no hubo venta)
  } ],                                       // solo sitios con total != 0 o previousTotal != 0; orden: categoría A, B, C, sin categoría; dentro, total desc, luego nombre
  categories: [ {
    category,                                // "A" | "B" | "C" | null
    kioskCount,                              // sitios de `sites` con esa categoría
    total, previousTotal, growthPercent,
    sharePercent                             // % del total de todos los sitios en el periodo actual (0 si el total es 0)
  } ]                                        // una fila por categoría presente en `sites`, orden A, B, C, null
}
```

## Cambios a respuestas existentes (Addendum 2)
- `kioskOptions[]` agrega `category` (String, A/B/C o null).
- `breakdowns.byKiosk[]` (`BreakdownRow`) agrega `category` (String o null; se deja null en los demás desgloses y canales). Los demás campos no cambian.

## Frontend (comportamiento esperado)
- Sección nueva en la pestaña Kioskos, "Mapa de calor de kioscos", que carga su propio endpoint (carga diferida, estados vacío/error propios).
- Calendario de calor del total de kioscos (o del kiosco elegido en el selector, usando `dailySeries` de `/dashboard/kiosks`) reutilizando `OnlineHeatmap` con la paleta de kioscos.
- Matriz kiosco × día y matriz kiosco × día de la semana (promedio), sombreadas contra la mediana/promedio de cada kiosco, con insignia de clasificación A/B/C y filtro por clasificación.
- Resumen por clasificación y panel de insights (días con más venta, día de la semana más fuerte, kiosco líder, mayor crecimiento/caída, peso de la categoría A, patrón común entre kioscos).
