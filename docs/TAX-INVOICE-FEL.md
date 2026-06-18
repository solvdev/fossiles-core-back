# Facturación electrónica (FEL) — arquitectura

Documento técnico del módulo unificado de facturas FEL en Fossiles Core.

## Resumen

Todas las facturas electrónicas viven en **`tax_invoice`** + **`tax_invoice_line`**. Los orígenes (`source_type`) son:

| Origen | `source_type` | Disparo |
|--------|---------------|---------|
| POS kiosko | `KIOSK_SALE` | Automático con NIT; CF solo si el cajero marca «Emitir factura» |
| Venta online | `ONLINE_SALE` | Bajo demanda desde Ventas Online |
| Contabilidad | `MANUAL` | Formulario manual + certificación inmediata |

Las ventas enlazan la factura con `kiosk_sale.invoice_id` u `online_sale.invoice_id`. Las columnas `fel_*` en `kiosk_sale` se mantienen sincronizadas (solo lectura temporal; eliminación planificada en fase 2).

## Migración

Ejecutar en PostgreSQL:

```bash
psql ... -f scripts/migration-tax-invoice.sql
psql ... -f scripts/migration-tax-invoice-attempt.sql
psql ... -f scripts/migration-tax-invoice-certified-xml.sql
```

Incluye backfill de ventas POS que ya tenían `fel_*`.

## XML certificado (`fel_certified_xml`)

Al certificar con éxito, INFILE devuelve el campo **`xml_certificado`** (Base64). El backend lo decodifica y lo guarda en **`tax_invoice.fel_certified_xml`**.

| Uso | Detalle |
|-----|---------|
| Contenido | DTE XML autorizado: emisor, receptor, líneas, impuestos, UUID, firma digital del certificador |
| Descarga | `GET /api/tax-invoices/{id}/certified-xml` |
| Respuesta API | `hasCertifiedXml: true` cuando hay archivo almacenado |
| Representación gráfica | El XML no es un PDF; con esos datos se puede generar después HTML/PDF + QR SAT (fase 2) |

Facturas certificadas **antes** de esta migración no tendrán XML almacenado (no se puede recuperar de INFILE salvo reintento dentro de 24 h).

## Bitácora de intentos (`tax_invoice_attempt`)

Cada llamada a certificación FEL (emisión o reintento) registra un intento **independiente** del estado actual en `tax_invoice`:

| Campo | Contenido |
|-------|-----------|
| `attempt_number` | Correlativo por factura (1, 2, 3…) |
| `action` | `ISSUE` (primera emisión) o `RETRY` |
| `status` | Resultado: `CERTIFIED`, `FAILED`, `SKIPPED` |
| Receptor / montos | Snapshot al momento del intento |
| `lines_json` | Detalle de líneas enviadas |
| FEL | `fel_transaction_id`, uuid, serie, número, error |
| `fel_enabled` | Si el servidor tenía FEL activo |

Consulta en BD:

```sql
SELECT attempt_number, action, status, total_amount, fel_error, created_at
FROM tax_invoice_attempt
WHERE tax_invoice_id = :id
ORDER BY attempt_number DESC;
```

## API REST

Base: `/api/tax-invoices`

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/` | Listado (filtros: `sourceType`, `status`, `customerTaxId`, `fromDate`, `toDate`) |
| GET | `/{id}` | Detalle + líneas + bitácora de intentos |
| GET | `/{id}/attempts` | Solo bitácora de intentos |
| GET | `/{id}/certified-xml` | Descarga XML del DTE certificado |
| POST | `/manual` | Factura manual (Contabilidad) |
| POST | `/from-kiosk-sale/{saleId}` | Emitir / reintentar POS |
| POST | `/from-online-sale/{saleId}` | Emitir venta online |
| POST | `/{id}/retry` | Reintento FEL (mismo `fel_transaction_id` 24h INFILE) |

## Servicios backend

- **`TaxInvoiceService`** — orquestación: persistencia, certificación, reintento, listado.
- **`TaxInvoiceAttemptService`** — bitácora append-only de cada intento FEL.
- **`FelFactXmlBuilder`** — XML FACT genérico desde `TaxInvoiceDocument`.
- **`KioskSaleInvoiceMapper`** / **`OnlineSaleInvoiceMapper`** — mapeo venta → documento.
- **`FelPosInvoiceService`** — deprecado; delega a `TaxInvoiceService`.

## Regla POS

| NIT / CF | `requestInvoice` | Emite FEL |
|----------|------------------|-----------|
| NIT ≠ CF | (ignorado) | Sí |
| CF | `false` / null | No |
| CF | `true` | Sí |

Si la certificación falla y `fel.emission.required=false`, la venta se guarda y la factura queda `FAILED`.

## Frontend

| Ruta | Vista |
|------|-------|
| `/admin/accounting/invoices` | Listado general |
| `/admin/accounting/invoices/new` | Factura manual |
| `/admin/accounting/invoices/:id` | Detalle + reintento + **bitácora de intentos** |

Permisos (sincronizar desde rutas):

- `CONTABILIDAD.FACTURAS.VER`
- `CONTABILIDAD.FACTURAS.CREAR`
- `CONTABILIDAD.FACTURAS.CERTIFICAR`
- `CONTABILIDAD.FACTURAS.ANULAR`

`/admin/invoicing` redirige al listado de Contabilidad.

## Configuración

Ver `fel.emission.*` en `application.properties` y `docs/POS-KIOSKO.md`. Ambiente de pruebas INFILE:

```properties
fel.emission.enabled=true
fel.emission.test-mode=true
fel.emission.required=false
```

## Fuera de alcance (fase 2)

- Notas de crédito (`NCRE`)
- Anulación FEL desde Contabilidad
- Asientos contables automáticos
- PDF representación gráfica con QR SAT (generar desde `fel_certified_xml` o datos de factura)
