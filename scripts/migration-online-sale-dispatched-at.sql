-- Fecha y hora real de despacho de una venta en línea (cuando pasa a ENVIADO).
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- La columna acepta vacío: las ventas ya despachadas quedan sin fecha (no se inventa una).
-- Rollback: scripts/rollback-online-sale-dispatched-at.sql

ALTER TABLE online_sale ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMP;

COMMENT ON COLUMN online_sale.dispatched_at IS
    'Momento del despacho real (ENVIADO). Vacío en ventas anteriores a esta columna y en las importadas por CSV.';
