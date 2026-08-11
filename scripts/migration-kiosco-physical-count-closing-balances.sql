-- Fin. congelado al cerrar conteo físico (Ini. del siguiente periodo).
ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS closing_balances_data TEXT;

COMMENT ON COLUMN kiosco_physical_count.closing_balances_data IS
    'JSON snapshot de Fin. por producto/color (/talla) al cerrar; fuente del Ini. del siguiente conteo.';
