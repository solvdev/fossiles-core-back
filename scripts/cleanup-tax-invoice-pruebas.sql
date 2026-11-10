-- Limpieza de facturas FEL de PRUEBA anteriores al arranque real (2026-11-02).
--
-- Borra:
--   A) DTE certificados en el sandbox INFILE (serie "**PRUEBAS**") que no son del kiosco de demostración:
--      las pruebas de junio 2026 (14 al 26-jun), TINV-000003 … TINV-000026 (19 documentos).
--      Cuatro eran de ventas en línea reales (1389, 1390, 1391, 1449) que nunca tuvieron factura real:
--      esas ventas quedan sin factura (invoice_id = NULL), que es su estado verdadero.
--   B) Borradores / fallidas POS cuya venta ya no existe (TINV-000027, A38-1).
--
-- NO borra (se ocultan en Facturas FEL con "Incluir documentos de prueba" apagado):
--   - Facturas ATST-* del KIOSCO DE DEMOSTRACION (ventas test_sale = true): el kiosko sigue
--     usándose para pruebas; si se borran, el backfill POS las volvería a crear como borradores.
-- NO borra (decidir aparte):
--   - TINV-000020 (FAILED, venta en línea 1238 real sin factura).
--   - Ventas POS de Q2.00 en kioskos reales (facturas #40 y A15-6108 #74).
--
-- Respaldo: tablas *_bak_pruebas_20261102 (se pueden borrar cuando se confirme).
-- Probado en Postgres local (copia de prod 2026-11-02): 21 facturas, 32 líneas, 22 intentos.

BEGIN;

CREATE TEMP TABLE pruebas_ids ON COMMIT DROP AS
SELECT t.id
FROM tax_invoice t
WHERE (t.fel_serie = '**PRUEBAS**'
       AND NOT EXISTS (SELECT 1 FROM kiosk_sale k
                       WHERE t.source_type = 'KIOSK_SALE' AND k.id = t.source_id AND k.test_sale = true))
   OR (t.source_type = 'KIOSK_SALE'
       AND t.status IN ('DRAFT', 'FAILED', 'SKIPPED')
       AND NOT EXISTS (SELECT 1 FROM kiosk_sale k WHERE k.id = t.source_id));

-- Revisión antes de borrar: debe listar 21 filas.
SELECT t.id, t.source_type, t.source_id, t.status, t.internal_number, t.fel_serie, t.total_amount
FROM tax_invoice t
WHERE t.id IN (SELECT id FROM pruebas_ids)
ORDER BY t.id;

CREATE TABLE IF NOT EXISTS tax_invoice_bak_pruebas_20261102 AS
SELECT * FROM tax_invoice WHERE id IN (SELECT id FROM pruebas_ids);
CREATE TABLE IF NOT EXISTS tax_invoice_line_bak_pruebas_20261102 AS
SELECT * FROM tax_invoice_line WHERE tax_invoice_id IN (SELECT id FROM pruebas_ids);
CREATE TABLE IF NOT EXISTS tax_invoice_attempt_bak_pruebas_20261102 AS
SELECT * FROM tax_invoice_attempt WHERE tax_invoice_id IN (SELECT id FROM pruebas_ids);
CREATE TABLE IF NOT EXISTS online_sale_invoice_bak_pruebas_20261102 AS
SELECT id, invoice_id FROM online_sale WHERE invoice_id IN (SELECT id FROM pruebas_ids);
CREATE TABLE IF NOT EXISTS kiosk_sale_invoice_bak_pruebas_20261102 AS
SELECT id, invoice_id FROM kiosk_sale WHERE invoice_id IN (SELECT id FROM pruebas_ids);

UPDATE online_sale SET invoice_id = NULL WHERE invoice_id IN (SELECT id FROM pruebas_ids);
UPDATE kiosk_sale SET invoice_id = NULL WHERE invoice_id IN (SELECT id FROM pruebas_ids);

-- tax_invoice_line y tax_invoice_attempt se borran en cascada.
DELETE FROM tax_invoice WHERE id IN (SELECT id FROM pruebas_ids);

SELECT
    (SELECT COUNT(*) FROM tax_invoice_bak_pruebas_20261102) AS facturas_respaldadas,
    (SELECT COUNT(*) FROM tax_invoice WHERE fel_serie = '**PRUEBAS**') AS sandbox_restantes_demo;

COMMIT;
