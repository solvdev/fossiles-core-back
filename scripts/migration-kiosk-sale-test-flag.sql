-- Ventas POS marcadas como piloto/prueba (no cuentan en dashboard ni reportes prod).
-- Nuevas ventas heredan locations.pos_test_mode (ver migration-location-pos-test-mode.sql).

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS test_sale BOOLEAN NOT NULL DEFAULT false;

-- Backfill: facturas con serie de ambiente INFILE pruebas
UPDATE kiosk_sale
SET test_sale = true
WHERE test_sale = false
  AND fel_serie ILIKE '%PRUEBAS%';

-- Opcional: marcar manualmente ventas piloto sin FEL, p. ej. Villa Lobos:
-- UPDATE kiosk_sale SET test_sale = true
-- WHERE kiosk_location_id = <id_villa_lobos> AND sale_date >= '2026-05-01';
