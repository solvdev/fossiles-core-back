-- Vincula desembolsos de caja a ventas POS (depósito neto = efectivo venta − desembolsos de esa venta).

ALTER TABLE kiosk_cash_expense
    ADD COLUMN IF NOT EXISTS kiosk_sale_id BIGINT NULL REFERENCES kiosk_sale (id);

CREATE INDEX IF NOT EXISTS idx_kiosk_cash_expense_sale
    ON kiosk_cash_expense (kiosk_sale_id, created_at DESC)
    WHERE kiosk_sale_id IS NOT NULL;

COMMENT ON COLUMN kiosk_cash_expense.kiosk_sale_id IS
    'Venta POS de la que sale el desembolso; NULL = gasto general de caja.';
