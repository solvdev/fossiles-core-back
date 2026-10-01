# Finanzas por kiosco — contrato de API y reglas de cálculo

Fuente de verdad compartida por backend, importador y frontend. Plan completo:
`C:\Users\eduar\.claude\plans\c-users-eduar-desktop-work-fossiles-docu-fluffy-toucan.md`.
DDL: `scripts/migration-kiosk-financials.sql`. Permisos: `scripts/seed-kiosk-financials-permissions.sql`.

## Convenciones

- Base: `/api/kiosk-financials`. JSON, `camelCase`. Sin wrapper de respuesta (igual que el resto del backend).
- Fechas `yyyy-MM-dd`. Montos en quetzales, IVA incluido (igual que `kiosk_sale.total_amount`), `number` con 2 decimales.
- **Porcentajes son decimales**: `0.18` = 18 %. La UI los muestra como %.
- Errores: `{timestamp, status, message}` (GlobalExceptionHandler existente); `BusinessException` → 400.
- Permisos (validados en servicio con `KioskFinancialsAccessGuard`; ADMIN/ADMIN_FULL_ACCESS siempre pasan):
  `KIOSCOS.FINANZAS.VER` (todos los GET), `KIOSCOS.FINANZAS.EDITAR` (PUT/POST de sitios y config), `KIOSCOS.FINANZAS.IMPORTAR` (imports).
- Mes = 1..12. `siteIds` = lista separada por comas (opcional; vacío = todos los sitios con datos).
- Timezone de negocio: `America/Guatemala` (`GuatemalaDateTime.today()`).

## Normalización de nombres de columnas Excel (alias)

`normalizeAlias(s)`: NFD → quitar marcas diacríticas → MAYÚSCULAS → eliminar todo lo que no sea `A-Z`, `0-9` o espacio (esto elimina el carácter corrupto U+FFFD) → colapsar espacios → trim.
Ej.: `"MIRAFLORES "`→`MIRAFLORES`, `"SANTALÙ"`→`SANTALU`, `"SANTAL\uFFFD"`→`SANTAL`.

## Fórmulas (paridad con los Excel de 2025) — `KioskPnlCalculator`

Entradas por sitio-mes: `V` ventas del mes (IVA incl.), tasas `pc` (costo producto), `sc` (comisión venta), `tc` (tarjeta), `tx` ("IVA"), costos fijos por categoría `CF_i`, `days` = días del mes.

| Concepto | Fórmula |
|---|---|
| Costo producto | `V × pc` |
| Comisión de venta | `(V / 1.12) × sc` |
| Comisión tarjeta | `V × tc` |
| IVA (carga) | `V × tx` |
| Total costos variables | suma de los 4 |
| Total costos fijos | `Σ CF_i` |
| Total costo operativo | variables + fijos |
| Diferencia | `V − total costo operativo` |
| Margen | `Diferencia / V` (null si V=0) |
| Punto de equilibrio | `CF / (1 − (sc + pc + tc + tx))` (null si denominador ≤ 0) |
| PE diario | `PE / days` (desviación consciente: el Excel usa 30/31) |
| % participación | `V_sitio / V_total` |
| % meta | `V / meta` (null si meta nula o 0) |

Redondeo: calcular con `BigDecimal` y redondear HALF_UP a 2 decimales sólo al presentar; porcentajes/margen con 4 decimales.
Valores de control (Enero 2025, total): ventas 1,090,923.30 · costo operativo 752,652.37 · diferencia 338,270.93 · margen 0.3101.

## Fuente de ventas (por sitio y fecha)

`goLiveEffective = pos_go_live_override ?? MIN(sale_date)` de `kiosk_sale` del `location_id` del sitio con `test_sale=false` y `status` no VOID/CANCELLED (regla `KioskPosService.countsForProductionMetrics`); null si no hay.
Para fecha `d`: si `goLiveEffective != null && d >= goLiveEffective` → POS (`SUM(total_amount)`); si no → `kiosk_daily_sales_hist`.
Filas hist con fecha ≥ goLive se ignoran en reportes y el importador avisa (WARNING `OVERLAPS_POS`).
`source` en respuestas: `HIST`, `POS` o `MIXED`.

## Sitios

`GET /sites` →
```json
[{"id":1,"name":"MIRAFLORES II","locationId":15,"locationCode":"MIRAF2","status":"ACTIVE","closedOn":null,
  "posGoLiveOverride":null,"posGoLiveDetected":"2026-07-21","goLiveEffective":"2026-07-21",
  "aliases":["MIRAFLORES"]}]
```
`POST /sites` `{name, status?, closedOn?}` → crea sitio histórico (`locationId` null). `PUT /sites/{id}` `{name?, status?, closedOn?, posGoLiveOverride?, aliases?:[string]}` (aliases se normalizan; conflicto de alias → 400; `null` en `posGoLiveOverride` lo limpia sólo si se envía la clave explícita `clearGoLiveOverride:true`).

## Configuración (costos, tasas, metas)

`GET /config?year=2026&siteId=&month=` →
```json
{"year":2026,
 "categories":[{"code":"ALQUILER","name":"Alquiler","sortOrder":1}],
 "sites":[{"siteId":1,"name":"MIRAFLORES II","status":"ACTIVE",
   "months":[{"month":1,"goal":130000,"productCostPct":0.18,"salesCommissionPct":0.04,"cardCommissionPct":0.025,"taxPct":0.025,
              "source":"EXCEL","costs":{"ALQUILER":14674.89,"LUZ":0,"BONO_14":263.86},"complete":true}]}]}
```
Un mes sin configuración se devuelve con campos `null`, `costs:{}`, `complete:false`. `complete` = meta no nula + 4 tasas no nulas + las 10 categorías con valor (0 cuenta).

`PUT /config/bulk`
```json
{"year":2026,"changes":[{"siteId":1,"month":3,"goal":120000,"productCostPct":0.18,
  "costs":{"ALQUILER":15000,"LUZ":null}}]}
```
Semántica: sólo se tocan las claves presentes; `null` en un costo borra la fila; se marca `source=MANUAL`. Respuesta `{"updatedMonths":1,"updatedCells":3}`. Todo en una transacción.

`POST /config/copy`
```json
{"fromYear":2025,"fromMonth":12,"toYear":2026,"toMonths":[1,2,3,4,5,6,7,8,9,10,11,12],
 "siteIds":null,"include":["COSTS","RATES","GOALS"],"overwrite":false}
```
`overwrite=false` no pisa celdas ya llenas. `source=COPIED`. Respuesta `{"copiedMonths":n,"copiedCells":n,"skippedCells":n}`.

## Reportes

`GET /pnl?year=2026&month=&siteIds=` — `month` omitido = año completo (suma de meses con ventas).
```json
{"year":2026,"month":8,
 "sites":[{"siteId":1,"name":"MIRAFLORES II","source":"MIXED","complete":true,
   "sales":97476.25,"goal":130000,"goalPct":0.75,"participationPct":0.06,
   "variable":{"productCost":0,"salesCommission":0,"cardCommission":0,"tax":0,"total":0},
   "fixed":{"byCategory":{"ALQUILER":14674.89},"total":20934.42},
   "totalCost":0,"difference":0,"margin":0.0,"breakEven":0,"breakEvenDaily":0,"daysWithSales":29}],
 "totals":{ "...misma forma sin siteId/name...": 0}}
```
Con `month` omitido cada sitio incluye además `byMonth:[{month,sales,totalCost,difference,margin}]`.

`GET /daily-matrix?year=&month=&siteIds=`
```json
{"year":2025,"month":1,"sites":[{"siteId":1,"name":"MIRAFLORES II"}],
 "days":[{"date":"2025-01-02","values":{"1":6044.5},"total":47992.75,"cumulative":47992.75}],
 "siteTotals":{"1":74038.8},"grandTotal":1090923.3}
```
`values[siteId]` = `null` si no hay dato (≠ 0).

`GET /compare?year=2026&baseYear=2025&fromMonth=1&toMonth=9&mode=SAME_PERIOD&siteIds=`
`mode`: `SAME_PERIOD` (por sitio compara `[max(goLive, 1-ene-year) .. min(hoy, fin toMonth)]` contra las mismas fechas de `baseYear`; 29-feb → 28-feb) o `FULL_MONTH` (meses completos).
```json
{"year":2026,"baseYear":2025,"mode":"SAME_PERIOD","asOf":"2026-09-30",
 "sites":[{"siteId":1,"name":"...","periodFrom":"2026-07-21","periodTo":"2026-09-30",
   "basePeriodFrom":"2025-07-21","basePeriodTo":"2025-09-30",
   "sales":0,"baseSales":0,"delta":0,"deltaPct":0.0,"goalPct":0.0,"baseGoalPct":0.0,
   "margin":0.0,"baseMargin":0.0,"difference":0,"baseDifference":0}],
 "totals":{"sales":0,"baseSales":0,"delta":0,"deltaPct":0.0,"margin":0.0,"baseMargin":0.0,"difference":0,"baseDifference":0},
 "monthly":[{"month":7,"sales":0,"baseSales":0,"totalCost":0,"baseTotalCost":0,"margin":0.0,"baseMargin":0.0}]}
```
`deltaPct` null si `baseSales`=0. Costos del período parcial se prorratean por días (`costoMes × díasPeriodoEnMes/díasMes`).

`GET /completeness?year=` → `{"year":2025,"sites":[{"siteId":1,"name":"...","months":[{"month":1,"hasSales":true,"hasCosts":true,"hasGoal":true}]}]}`.

## Importación Excel

`POST /imports/preview` (`multipart/form-data`, campo `files` repetido) → 
```json
{"files":[{
  "fileName":"VENTAS AGOSTO 2025.xlsx","sha256":"...","year":2025,"month":8,"sheetName":"Reporte de Vtas  orig.",
  "alreadyImported":{"batchId":3,"createdAt":"2026-09-30T10:00:00"},
  "columns":[{"excelName":"PLAZA CEMACO","normalized":"PLAZA CEMACO","matchedSiteId":19,"matchStatus":"MATCHED"}],
  "issues":[{"id":"i1","severity":"BLOCKING","code":"NON_NUMERIC_CELL","message":"Celda con texto '1254..6'",
             "excelName":"PLAZA CEMACO","date":"2025-08-21","cell":"R29","rawValue":"1254..6","suggestion":1254.6}],
  "data":{
    "days":[{"date":"2025-08-01","values":{"PLAZA CEMACO":525.6,"ESKALA":null}}],
    "goals":{"PLAZA CEMACO":100000},
    "rates":{"PLAZA CEMACO":{"productCostPct":0.18,"salesCommissionPct":0.04,"cardCommissionPct":0.02,"taxPct":0.025}},
    "costs":{"PLAZA CEMACO":{"ALQUILER":7652.51,"LUZ":null}}},
  "stats":{"columns":37,"days":31,"salesCells":1090,"salesTotal":2000000.0,"sheetTotal":2000000.0}}]}
```
- `matchStatus`: `MATCHED` | `UNMATCHED` (requiere mapear o crear sitio histórico) | `AMBIGUOUS`.
- `severity`: `BLOCKING` (impide commit hasta resolver) | `WARNING` | `INFO`.
- Códigos de issue: `NON_NUMERIC_CELL` (BLOCKING, con `suggestion` si es reparable), `NEGATIVE_VALUE` (BLOCKING), `UNMATCHED_COLUMN` (BLOCKING), `TOTAL_MISMATCH` (WARNING), `OUT_OF_MONTH_VALUE` (WARNING), `OUTLIER` (INFO, valor > 6× mediana y > 3000), `MISSING_COSTS` (WARNING: ventas sin costos completos), `MISSING_GOAL` (WARNING), `OVERLAPS_POS` (WARNING), `DUPLICATE_FILE` (WARNING), `LAYOUT_ASSUMPTION` (INFO).

`POST /imports/commit`
```json
{"replaceExisting":true,
 "files":[{"fileName":"...","sha256":"...","year":2025,"month":8,
   "siteMapping":{"PLAZA CEMACO":{"siteId":19},"NUEVO KIOSCO":{"create":{"name":"NUEVO KIOSCO"}}},
   "resolutions":{"i1":1254.6},
   "data":{"...igual que preview.data..."}}]}
```
`resolutions[issueId]` = número que reemplaza la celda, o `"IGNORE"` (celda queda sin dato). El servidor revalida todo (nunca confía en el cliente): rechaza con 400 si quedan BLOCKING sin resolver, si un sitio no tiene mapeo, o si hay negativos. Crea alias nuevos para columnas mapeadas manualmente. Reemplazo por (sitio, año-mes): borra ventas/config/costos del mes de esos sitios y escribe los nuevos (con `import_batch_id`). Respuesta:
`{"batches":[{"batchId":7,"fileName":"...","year":2025,"month":8,"salesRows":1090,"configRows":37,"costRows":370,"replacedRows":0,"warnings":[]}]}`.

`POST /imports/{batchId}/revert` → borra lo escrito por ese lote y marca `REVERTED`. `GET /imports` → lista de lotes.

### Ajustes acordados tras implementar el importador (prevalecen sobre lo anterior)

- **`data.blockedCells`**: el preview incluye dentro de `data` la lista `blockedCells:[{issueId,code,excelName,date,cell,rawValue}]`. El cliente **debe reenviar `data` sin modificar** en el commit; el servidor rechaza celdas bloqueadas sin resolución (número o `"IGNORE"`) y resoluciones que no correspondan a una celda bloqueada. Los `issue.id` son secuenciales por archivo (`i1`, `i2`…).
- Códigos extra: `DUPLICATE_COLUMN` (BLOCKING), `FILE_ERROR` (BLOCKING; un archivo ilegible, p. ej. `~$…xlsx`, se reporta por archivo sin tumbar todo el preview). Una columna `AMBIGUOUS` (dos columnas al mismo sitio) se reporta con issue `UNMATCHED_COLUMN`. `alreadyImported` trae `sameFile`; `columns[]` trae `matchedSiteName`.
- `TOTAL_MISMATCH` compara la hoja contra la suma **con el valor sugerido** de las celdas de texto reparables.
- Texto en filas de meta/tasas/costos → WARNING `NON_NUMERIC_CELL`, se guarda null.
- Meta 0 se guarda como NULL (con nota `MISSING_GOAL`). Se escribe `kiosk_period_config` sólo si hay meta o alguna tasa.
- `replaceExisting=false` → el commit se rechaza si el sitio ya tiene ventas/config/costos de ese mes. Dos archivos del mismo año-mes en un commit se rechazan.
- Revert: borra por `import_batch_id` (en config sólo `source='EXCEL'`); no borra sitios ni alias creados; 404 si el lote no existe; rechaza si ya está REVERTED.
- `create` en `siteMapping` reutiliza un sitio existente con el mismo nombre (sin distinguir mayúsculas); un alias ya asignado a otro sitio → 400.
- Límite multipart: valores por defecto de Spring (1 MB por archivo, 10 MB por request); los Excel pesan ~100 KB.

## Estructura Excel origen (para el parser)

Hoja: primera hoja que no sea `Hoja1` (nombre real `"Reporte de Vtas  orig."`). Bloques (etiquetas en la columna a la izquierda de la 1a columna de kiosco, A o B): fila de encabezado (`Fecha` | nombres de kiosco hasta `Total por día`), 31 filas de fecha (pueden desbordar al mes siguiente: ignorar), `Total`, `% Participacion`, `METAS`, `% DE META`, `COSTOS`, `Costos Variables` (`Costo del Pdcto`, `Comision de venta`, `Comision tarjeta`, `IVA`: cada uno tiene la fila de tasa seguida de una fila calculada — importar sólo la tasa), `Total CI` (variables), `Costos Fijos` con 10 categorías (etiquetas con carácter corrupto: comparar por prefijo normalizado), `Total CI` (fijos), `Total Cto Oper.`, `Diferencia Vta`, `MARGEN`, `Punto de Equilibrio`, `PE DIARIO`. Puede haber una columna vacía intercalada entre kioscos. Las columnas tras `Total por día` (Acumulado, y bloque 2024) se ignoran. Archivos reales: `Documentacion/reportesventas/VENTAS <MES> 2025.xlsx`.

## Ajustes acordados tras implementar el backend núcleo (prevalecen sobre lo anterior)

- **`PUT /config/bulk` — clave ausente vs `null` explícito:** para `goal` y las 4 tasas, una clave ausente NO toca el valor y un `null` explícito lo BORRA. Para `costs`, `null` en una categoría borra la fila y una categoría ausente no se toca. Tasas deben estar entre 0 y 1 (`18` → 400; enviar `0.18`). Un cambio sólo de costos no crea fila en `kiosk_period_config`.
- **`/compare`:** el período inicia en `max(goLive, primer día de fromMonth)`. Costos y metas del período parcial se prorratean por días; un mes se omite (sin costos fijos) si ambos años tienen 0 ventas en esa porción. 29-feb → 28-feb en el año base.
- **Selección de sitios:** con `siteIds` vacío, `/pnl`, `/daily-matrix` y `/compare` incluyen sólo sitios con ventas en el período; con `siteIds` dados se incluyen siempre; ids desconocidos → 400. `GET /config` devuelve todos los sitios (incluidos cerrados) con los 12 meses.
- **Totales de año:** el punto de equilibrio anual y el de la fila `totals` son la suma de los PE mensuales / por sitio; `breakEvenDaily` divide por los días de los meses incluidos; `totals.daysWithSales` = fechas distintas con ventas.
- El corte por go-live sólo aplica a sitios con `locationId`; un sitio histórico con override igual lee todas sus filas hist.
- `POST /sites` crea también el alias igual al nombre normalizado si está libre.

## Segundo formato de reporte (abril 2026 en adelante) y supervisión (prevalece sobre lo anterior)

No habrá Excel de costos aparte: los costos se leen de los mismos reportes de ventas mensuales. El importador acepta **los dos formatos, incluso mezclados en una misma carga**; el formato se detecta por archivo según la estructura de la hoja (nunca por el nombre ni por una opción del usuario).

| | Formato anterior (`LEGACY`) | Formato nuevo (`SHEET_YEAR`) |
|---|---|---|
| Ejemplos | `VENTAS ENERO 2025.xlsx`, `VENTAS MARZO 2026.xlsx` | `reporte de ventas abril.xlsx`, `Reporte de ventas Mayo 2026.xlsx` |
| Hoja | `Reporte de Vtas  orig.` (encabezado `Fecha … Total por día`) | `ventas 2025`, `ventas 2026`, `anita`, `gabriela`, `ANALISIS DE COSTO FIJO` |
| Hoja que se lee | la primera que no sea `Hoja1` | la `ventas <año>` de año más reciente (encabezado `kiosco` en A1); las demás se omiten (INFO) |
| Tasas | fila de tasa + fila calculada | sólo el monto; la tasa va en la etiqueta (`costo del producto (18%)`) |
| Mes y año | fechas de la hoja | año = nombre de la hoja; mes = nombre del archivo (respaldo: fechas). **Editable** |

Reglas del formato nuevo:
- Kioscos = columnas desde B hasta la primera de `venta del día` / `acumulado` / columna con el año / `diferencia`. Filas de fecha = entre el encabezado y la fila `TOTAL`; de la columna de fechas sólo se usa el **número de día** (la hoja arrastra el mes/año de la plantilla anterior: el reporte de abril trae fechas de enero). Un día de relleno (31 en un mes de 30) se ignora. Se lee por etiqueta, no por número de fila (mayo trae una fila extra).
- Tasa derivada = monto del kiosco / ventas del mes (fila `TOTAL`). Si coincide (±0.0005) con el porcentaje de la etiqueta se usa el de la etiqueta; monto 0 ⇒ tasa 0 (p. ej. comisión de venta, que sólo aplica a Miraflores); kiosco sin ventas ⇒ tasas `null`. La comisión de venta se aplica como `(V / 1.12) × tasa` (la fórmula del contrato, no la del Excel, que usa `V × tasa`).
- Etiquetas de costos fijos: `Salarios encargadas (MOD)` → `SALARIOS_MO_INDIRECTA` y `Salarios suplentes (MOI)` → `SALARIOS_MO_DIRECTA` (por posición y monto; las siglas del Excel están invertidas respecto al nombre de la categoría). `supervisión` → `SUPERVISION`.
- Si la fila de supervisión no existe o viene vacía se calcula con la fórmula de abajo (INFO), usando como kioscos activos las columnas de kiosco del archivo.

**`POST /imports/preview`** (cambios): parámetro opcional `periods` (JSON en el formulario multipart): `{"<nombre de archivo>":{"year":2026,"month":4}}`. Sólo corrige archivos del formato nuevo; el formato anterior lo ignora. Cada elemento de `files[]` agrega `format` (`LEGACY` | `SHEET_YEAR`), `periodSource` (`DATES` | `FILE_NAME` | `OVERRIDE`) y `periodEditable` (true sólo en el formato nuevo). Si las fechas de la hoja no coinciden con el período usado se agrega un WARNING `LAYOUT_ASSUMPTION`. El commit no cambia: recibe `year`/`month` del archivo y revalida que los días estén dentro del mes.

**Categoría `SUPERVISION`** (11.ª de costos fijos, “Supervisión”): `((((Salarios MO indirecta + Bonificación) × 2) × 14) / 12) / kioscos activos`, a 2 decimales. Es **opcional**: los meses anteriores a 2026 no la traen y no cuenta para `complete` (`/config`) ni `hasCosts` (`/completeness`) ni `/compare`. Requiere ejecutar a mano `scripts/migration-kiosk-financials-supervision.sql`; mientras no exista en `kiosk_cost_category`, un commit con monto de supervisión se rechaza con 400 (un monto `null` se ignora). Implementación: `KioskSupervisionCost`.

## POS > Resumen (comparativo contra el año anterior)

`GET /api/kiosk-pos/dashboard/manager` (`KioskPosService.getManagerDashboard`):
- `todayLastYear` y el nuevo `monthToDateLastYear` (1 → día de hoy, un año atrás) salen de `KioskSalesSourceResolver` por el sitio cuyo `location_id` es el kiosco (2025 vive en `kiosk_daily_sales_hist`). Sin sitio, se calculan sólo con ventas POS como antes.
- `Metric.count` es `null` en esas dos métricas (el histórico sólo guarda montos diarios). Nuevo `growthMonthToDateVsLastYearPercent`. Día sin operación = 0.00.
- Las ventas de prueba (`test_sale = true`) no cuentan en ninguna métrica del dashboard.

## Sitios externos fuera de los reportes (Entrecueros Pueblito)

`kiosk_site.exclude_from_reports` (BOOLEAN, default false; `scripts/migration-kiosk-financials-exclude-reports.sql`, **ejecutar antes de desplegar**). Un sitio marcado no aparece en `/pnl`, `/daily-matrix`, `/compare`, `/completeness` ni `/config` (costos y metas), y pedirlo por `siteIds` responde 400 (“Sitio no encontrado”). `GET /sites` lo sigue devolviendo con `excludeFromReports:true` para poder desmarcarlo (`PUT /sites/{id}` `{excludeFromReports}`; panel “Sitios”). No se borra ningún dato. El front también lo oculta del filtro de kioscos y del importador.

## Filtro por supervisora

`GET /api/kiosk-financials/supervisors` (permiso `KIOSCOS.FINANZAS.VER`) → `{"supervisors":[{"userId":7,"name":"Ana Pérez","siteIds":[1,2]}],"unassignedSiteIds":[4]}`. Lee con SQL directo `kiosk_supervisor_assignment` (módulo "Supervisoras y kioscos", rama `main`; mismo enfoque que `kiosk_monthly_goal`) y traduce `kiosk_location_id` → `kiosk_site.id`. Sólo sitios que entran a los reportes (sin externos); una supervisora sin kioscos visibles se omite; `unassignedSiteIds` = kioscos con POS que nadie tiene asignados. Si la tabla no existe devuelve listas vacías. No hay parámetro nuevo en los reportes: la pantalla convierte la supervisora en `siteIds` (marcar/desmarcar supervisoras agrega/quita sus kioscos en el filtro "Kioscos"; sin ninguna marcada = todos).

## Comparación del Resumen (`/compare`, `mode`)

Los valores no cambian (`SAME_PERIOD`, `FULL_MONTH`); en pantalla se llaman **"Mismas fechas"** y **"Meses completos"**, con una frase que muestra las fechas reales que se comparan (`describeComparison`).

## Método del punto de equilibrio (configuración persistente)

Es un **ajuste global** guardado en `kiosk_financial_setting` (`BREAK_EVEN_MODE`, clave/valor; `scripts/migration-kiosk-financials-settings.sql`, ejecutar a mano; sin la tabla se usa `RATES` y no se puede guardar). `GET /api/kiosk-financials/settings` (VER) → `{breakEvenMode, flatBreakEvenRate:0.27, persisted, updatedAt}`; `PUT /settings` `{breakEvenMode:"RATES"|"FLAT"}` (EDITAR; otro valor → 400). Se cambia en **Costos por kiosco** y vale para todos los reportes y descargas hasta que se vuelva a cambiar: `GET /pnl` (y con él P&L, metas y equilibrio, estado “bajo equilibrio” y el Excel) lee el ajuste; **no** hay parámetro por petición. La respuesta de `/pnl` trae `breakEvenMode` (el vigente).
- `RATES`: `CF / (1 − (sc + pc + tc + tx))` con las tasas del kiosco (fórmula de los Excel de 2025 y enero 2026): es el punto donde la utilidad es 0.
- `FLAT`: `CF / (1 − 0.27)` para todos (fórmula de los Excel de abril 2026 en adelante: `=CF/(1-0.27)`, ≈ 18 % + 4 % + 2.87 % + 2.5 %). Más exigente con los kioscos sin comisión de venta (en septiembre 2026 marcaba “bajo equilibrio” a 4 kioscos con utilidad positiva).
Sólo cambia `breakEven` y `breakEvenDaily` (y lo que se deriva: estado “bajo equilibrio”, metas y equilibrio, Excel); costos, utilidad y margen siempre usan las tasas reales. `/compare` no usa punto de equilibrio. Selector en las pestañas P&L y Metas; el Excel descargado rotula la fila con el método. Pendiente de decisión: el denominador de `RATES` resta la comisión de venta como `sc` y no `sc/1.12` como la calcula la utilidad (efecto ≈ 0.6 % sólo en kioscos con comisión).
Nota de datos: en los Excel 2026 la comisión de venta es `=IF(% de meta >= 0.7, ventas × 4%, 0)` (sólo si el kiosco llega al 70 % de su meta); el Excel de septiembre “(1)” usa `B2` en vez de `B33` en Miraflores (Q88.71 en vez de ≈ Q3,369).

## Días sin sistema (corrección desde el Excel del reporte)

Kioscos que arrancaron en el POS a mitad de mes tienen Q 0.00 los días anteriores a su primera venta aunque el reporte Excel sí traiga venta. Esos días ya se leen de `kiosk_daily_sales_hist` (fecha < go-live), así que la corrección escribe sólo esas celdas ahí.

- `POST /api/kiosk-financials/imports/gap-fill/preview` (multipart: `file`; opcionales `year`+`month` para corregir el mes). Misma lectura de ambos formatos. Por kiosco (alias → sitio con `location_id`, no externo, con ventas POS) devuelve `candidates` (día < go-live, sistema = 0, reporte > 0), `differences` (mismo día, monto distinto: sólo informativo, p. ej. centavos o fechas corridas) y `afterGoLiveGaps` (sistema = 0 desde el go-live: no cubrible con este flujo). Además `ignoredColumns`, `skippedCells` (texto no numérico) y `totals`.
- `POST /api/kiosk-financials/imports/gap-fill/commit` (multipart: `file`, `siteIds` CSV, opcionales `year`/`month`). **Vuelve a leer el archivo y recalcula** (nunca confía en montos del cliente); cada sitio elegido debe tener candidatos (400 si no). Escribe sólo `kiosk_daily_sales_hist` con un lote `kiosk_import_batch` cuyo `file_name` empieza con `DIAS SIN SISTEMA - ` y con sha propio; no toca costos, tasas, metas, otros kioscos ni el POS, y nunca pisa un día con venta en el sistema. Revertible con `POST /imports/{batchId}/revert`. Estos lotes no cuentan como “mes ya importado” en el preview del importador completo.
- Permiso: `KIOSCOS.FINANZAS.IMPORTAR`. Implementación: `KioskGapFillService`, `KioskGapFillPlanner`.
- Limitación conocida: un kiosco que ya operaba en el POS y deja un día sin registrar a mitad de mes no se puede cubrir (el resolver ignora el histórico desde el go-live); se informa en `afterGoLiveGaps`.
