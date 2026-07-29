-- Monto real del voucher POS (puede diferir del total de factura / card_amount).
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS card_voucher_amount DECIMAL(12, 2),
    ADD COLUMN IF NOT EXISTS card2_voucher_amount DECIMAL(12, 2);

COMMENT ON COLUMN kiosk_sale.card_voucher_amount IS
    'Monto impreso en el voucher de la tarjeta 1. No altera total_amount ni FEL.';
COMMENT ON COLUMN kiosk_sale.card2_voucher_amount IS
    'Monto impreso en el voucher de la tarjeta 2 (pago dividido).';
