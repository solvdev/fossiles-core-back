-- ---------------------------------------------------------------------------
-- Finanzas por kiosco: ajustes globales (clave/valor). Hoy guarda el metodo del punto de equilibrio:
--   BREAK_EVEN_MODE = RATES (tasas reales de cada kiosco, por defecto) | FLAT (27 % fijo, como los Excel de 2026)
-- Se cambia desde Costos por kiosco y aplica en todos los reportes y descargas hasta que se vuelva a cambiar.
-- Ejecutar a mano (primero en fosstest, con pg_dump antes de produccion). Es idempotente. Sin esta tabla el
-- backend usa RATES y no deja guardar el cambio.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_financial_setting (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_by    BIGINT,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

INSERT INTO kiosk_financial_setting (setting_key, setting_value) VALUES ('BREAK_EVEN_MODE', 'RATES')
ON CONFLICT (setting_key) DO NOTHING;
