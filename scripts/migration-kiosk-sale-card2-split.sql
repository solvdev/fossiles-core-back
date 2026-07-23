-- Segunda tarjeta en ventas POS (pago dividido en dos tarjetas, mismo cobro).
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS card2_amount DECIMAL(12, 2),
    ADD COLUMN IF NOT EXISTS card2_auth_number VARCHAR(40),
    ADD COLUMN IF NOT EXISTS card2_last4 VARCHAR(4),
    ADD COLUMN IF NOT EXISTS card2_brand VARCHAR(10);
