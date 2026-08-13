-- =============================================================================
-- Corrige facturas que se anularon ante el SAT pero quedaron en DRAFT
-- (aparecían en "Sin firmar" en vez de "Anuladas").
-- Criterio: tienen fel_void_uuid / voided_at y ya no tienen fel_uuid.
-- =============================================================================

BEGIN;

-- Vista previa
SELECT
  ti.id,
  ti.internal_number,
  ti.status,
  ti.fel_uuid,
  ti.fel_void_uuid,
  ti.voided_at,
  ti.void_reason,
  ks.id AS kiosk_sale_id,
  ks.fel_status
FROM tax_invoice ti
LEFT JOIN kiosk_sale ks ON ks.invoice_id = ti.id
WHERE ti.status = 'DRAFT'
  AND ti.fel_void_uuid IS NOT NULL
  AND (ti.fel_uuid IS NULL OR btrim(ti.fel_uuid) = '')
ORDER BY ti.voided_at DESC NULLS LAST, ti.id DESC;

UPDATE tax_invoice
SET status = 'VOID',
    updated_at = NOW()
WHERE status = 'DRAFT'
  AND fel_void_uuid IS NOT NULL
  AND (fel_uuid IS NULL OR btrim(fel_uuid) = '');

UPDATE kiosk_sale ks
SET fel_status = 'VOID',
    fel_uuid = NULL,
    fel_serie = NULL,
    fel_numero = NULL,
    fel_certified_at = NULL,
    fel_error = ti.void_reason
FROM tax_invoice ti
WHERE ks.invoice_id = ti.id
  AND ti.status = 'VOID'
  AND ti.fel_void_uuid IS NOT NULL;

COMMIT;
