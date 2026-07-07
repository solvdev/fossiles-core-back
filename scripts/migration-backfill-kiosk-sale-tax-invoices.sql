-- Backfill de tax_invoice para ventas POS (kiosk_sale) que aún no tienen registro.
--
-- RECOMENDADO (genera correlativo interno correcto, líneas por item, enlace invoice_id):
--   POST /api/tax-invoices/backfill-kiosk-sales?dryRun=true
--   POST /api/tax-invoices/backfill-kiosk-sales
--   POST /api/tax-invoices/backfill-kiosk-sales?fromDate=2026-01-01&toDate=2026-03-31&kioskLocationId=12
--
-- También desde Contabilidad → Facturas electrónicas → "Generar borradores POS faltantes".
--
-- Una venta puntual:
--   POST /api/tax-invoices/draft-from-kiosk-sale/{saleId}

-- ---------------------------------------------------------------------------
-- 1) PREVIEW: ventas sin tax_invoice
-- ---------------------------------------------------------------------------
SELECT
    ks.id AS sale_id,
    ks.sale_number,
    ks.sale_date,
    ks.kiosk_location_id,
    l.name AS kiosk_name,
    l.internal_series_code,
    ks.customer_tax_id,
    ks.total_amount,
    ks.fel_status,
    ks.fel_uuid,
    ks.invoice_id AS orphan_invoice_id
FROM kiosk_sale ks
LEFT JOIN locations l ON l.id = ks.kiosk_location_id
WHERE NOT EXISTS (
    SELECT 1
    FROM tax_invoice ti
    WHERE ti.source_type = 'KIOSK_SALE'
      AND ti.source_id = ks.id
)
ORDER BY ks.sold_at ASC, ks.id ASC;

-- Conteo rápido
SELECT COUNT(*) AS ventas_sin_tax_invoice
FROM kiosk_sale ks
WHERE NOT EXISTS (
    SELECT 1
    FROM tax_invoice ti
    WHERE ti.source_type = 'KIOSK_SALE'
      AND ti.source_id = ks.id
);

-- ---------------------------------------------------------------------------
-- 2) Reparar invoice_id huérfano (kiosk_sale.invoice_id apunta a factura inexistente)
-- ---------------------------------------------------------------------------
UPDATE kiosk_sale ks
SET invoice_id = ti.id
FROM tax_invoice ti
WHERE ti.source_type = 'KIOSK_SALE'
  AND ti.source_id = ks.id
  AND (ks.invoice_id IS NULL OR ks.invoice_id <> ti.id);

-- ---------------------------------------------------------------------------
-- 3) NO usar INSERT masivo SQL para crear borradores nuevos.
--    El correlativo interno (A45-241) se genera en Java con lock por serie
--    (location_internal_number_sequence). Un INSERT manual puede duplicar o
--    saltar números.
--
-- Si necesitas SQL de emergencia, crea UNA factura de prueba y valida el
-- correlativo antes de masificar.
-- ---------------------------------------------------------------------------
