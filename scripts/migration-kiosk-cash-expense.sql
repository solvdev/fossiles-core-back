-- Gastos de efectivo durante sesión de caja POS (para cuadre: fondo + ventas - gastos)

CREATE TABLE IF NOT EXISTS kiosk_cash_expense (
    id                  BIGSERIAL PRIMARY KEY,
    cash_session_id     BIGINT NOT NULL REFERENCES kiosk_cash_session (id) ON DELETE CASCADE,
    amount              NUMERIC(12, 2) NOT NULL,
    description         VARCHAR(500) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id  BIGINT NOT NULL,
    CONSTRAINT chk_kiosk_cash_expense_amount CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_cash_expense_session
    ON kiosk_cash_expense (cash_session_id, created_at DESC);

COMMENT ON TABLE kiosk_cash_expense IS 'Salidas de efectivo del fondo de caja durante el turno (compras menores, etc.).';
