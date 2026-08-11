-- Reparación FOSS-33 (PROOSEV): venta sin talla + stock desfasado.
-- 1) Completa size_key en la VENTA del ledger.
-- 2) Tras ejecutar, en Ledger Lab: REPLAY STOCK del stock #8068
--    (o POST /api/.../kiosk-ledger-lab/stocks/8068/replay)
-- 3) Recargar el borrador del conteo 03–09 ago: Ini/Fin deben quedar en 0.

BEGIN;

UPDATE kiosco_movement
SET size_key = '34'
WHERE id = 3177
  AND movement_type = 'VENTA'
  AND kiosco_stock_id = 8068
  AND (size_key IS NULL OR btrim(size_key) = '');

-- Verificación rápida
SELECT id, movement_type, quantity, size_key, stock_before, stock_after, affects_stock, created_at
FROM kiosco_movement
WHERE kiosco_stock_id = 8068
ORDER BY created_at, id;

SELECT id, current_stock, sizes_data
FROM kiosco_stock
WHERE id = 8068;

COMMIT;
