-- Marca de tarjeta en ventas POS (VISA, MC, AMEX) para reporte de voucher.
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS card_brand VARCHAR(10);
