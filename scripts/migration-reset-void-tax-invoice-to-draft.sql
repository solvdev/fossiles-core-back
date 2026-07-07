-- Recupera facturas FEL anuladas con la lógica anterior (status VOID + UUID FEL aún presente).
-- Deja tax_invoice en DRAFT y limpia campos FEL en kiosk_sale para volver a certificar.
-- Ejecutar solo tras confirmar que la anulación ya fue aceptada por el SAT (fel_void_uuid poblado).

BEGIN;

UPDATE tax_invoice
SET status = 'DRAFT',
    fel_uuid = NULL,
    fel_serie = NULL,
    fel_numero = NULL,
    fel_error = NULL,
    fel_certified_at = NULL,
    fel_certified_xml = NULL,
    updated_at = NOW()
WHERE status = 'VOID'
  AND fel_void_uuid IS NOT NULL;

UPDATE kiosk_sale ks
SET fel_status = 'DRAFT',
    fel_uuid = NULL,
    fel_serie = NULL,
    fel_numero = NULL,
    fel_error = NULL,
    fel_certified_at = NULL
FROM tax_invoice ti
WHERE ks.invoice_id = ti.id
  AND ti.status = 'DRAFT'
  AND ti.fel_void_uuid IS NOT NULL;

-- Vista previa (descomentar para revisar antes del UPDATE):
-- SELECT ti.id, ti.internal_number, ti.status, ti.fel_uuid, ti.fel_void_uuid, ks.id AS sale_id, ks.fel_status, ks.fel_uuid
-- FROM tax_invoice ti
-- LEFT JOIN kiosk_sale ks ON ks.invoice_id = ti.id
-- WHERE ti.status = 'VOID' AND ti.fel_void_uuid IS NOT NULL;

COMMIT;
