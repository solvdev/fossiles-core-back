-- Boleta de depósito por venta POS en efectivo / mixto con efectivo.

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS deposit_slip_number VARCHAR(40),
    ADD COLUMN IF NOT EXISTS deposit_recorded_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deposit_recorded_by BIGINT;

CREATE INDEX IF NOT EXISTS idx_kiosk_sale_pending_deposit
    ON kiosk_sale (kiosk_location_id, sale_date DESC)
    WHERE deposit_slip_number IS NULL
      AND status = 'COMPLETED'
      AND test_sale = false
      AND (
          payment_method = 'EFECTIVO'
          OR (payment_method = 'MIXTO' AND cash_amount > 0)
      );
