-- Fondo inicial de caja POS configurable por kiosko (default Q300).
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS pos_opening_cash_amount NUMERIC(12, 2) NOT NULL DEFAULT 300;

COMMENT ON COLUMN locations.pos_opening_cash_amount IS
    'Fondo inicial al abrir caja POS en este kiosko. Las sesiones ya abiertas conservan su opening_amount.';

-- ---------------------------------------------------------------------------
-- Ejemplos (descomentar y ajustar id/código antes de ejecutar)
-- ---------------------------------------------------------------------------

-- 1) Configurar fondo Q500 para un kiosko (próximas aperturas):
-- UPDATE locations
-- SET pos_opening_cash_amount = 500.00
-- WHERE code = 'VILLALOBOS';

-- 2) Corregir una caja YA ABIERTA con fondo incorrecto (solo esa sesión):
-- UPDATE kiosk_cash_session s
-- SET opening_amount = 500.00
-- FROM locations l
-- WHERE s.kiosk_location_id = l.id
--   AND s.status = 'OPEN'
--   AND l.code = 'VILLALOBOS';

-- 3) Ver cajas abiertas y su fondo actual:
-- SELECT s.id AS session_id,
--        l.id AS kiosk_id,
--        l.code,
--        l.name,
--        l.pos_opening_cash_amount AS fondo_configurado,
--        s.opening_amount AS fondo_sesion,
--        s.opened_at,
--        s.status
-- FROM kiosk_cash_session s
-- JOIN locations l ON l.id = s.kiosk_location_id
-- WHERE s.status = 'OPEN'
-- ORDER BY s.opened_at DESC;
