-- Banco donde se depositó la boleta (G&T Continental / Industrial).

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS deposit_bank VARCHAR(40);

COMMENT ON COLUMN kiosk_sale.deposit_bank IS
    'Banco del depósito: GT_CONTINENTAL | INDUSTRIAL';
