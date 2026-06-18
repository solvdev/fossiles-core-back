-- POS Kiosko: cobro efectivo/cambio, promos COMBO, secuencia de venta, scope por kiosko
-- Ejecutar manualmente en PostgreSQL si no usa ddl-auto=update

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS amount_received NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS change_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS cash_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS card_amount NUMERIC(12, 2);

ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS kiosk_location_id BIGINT,
    ADD COLUMN IF NOT EXISTS combo_buy_qty INTEGER,
    ADD COLUMN IF NOT EXISTS combo_pay_qty INTEGER;

CREATE TABLE IF NOT EXISTS kiosk_sale_sequence (
    sale_date DATE PRIMARY KEY,
    last_number INTEGER NOT NULL DEFAULT 0
);
