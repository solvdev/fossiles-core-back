-- Herraje del producto entregado en boletas de cambio (stock kiosco NUEVO/VIEJO).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosk_exchange_slip
    ADD COLUMN IF NOT EXISTS given_hardware_condition VARCHAR(20);

COMMENT ON COLUMN kiosk_exchange_slip.given_hardware_condition IS
    'Herraje del producto entregado (NUEVO/VIEJO) para egreso en stock kiosco.';
