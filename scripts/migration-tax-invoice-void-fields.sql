-- Deploy POS: recepción envíos, caja, anulación FEL
-- Ejecutar en prod ANTES de reiniciar el backend.

-- Notas por línea al confirmar recepción (product_shipment_detail)
ALTER TABLE product_shipment_detail
    ADD COLUMN IF NOT EXISTS received_line_notes VARCHAR(500);

-- Campos de anulación FEL en tax_invoice
ALTER TABLE tax_invoice
    ADD COLUMN IF NOT EXISTS voided_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS fel_void_uuid VARCHAR(64);


CREATE TABLE IF NOT EXISTS kiosk_cash_session (
    id BIGSERIAL PRIMARY KEY,
    kiosk_location_id BIGINT NOT NULL REFERENCES locations(id),
    opened_by_user_id BIGINT NOT NULL,
    opened_at TIMESTAMP NOT NULL DEFAULT NOW(),
    opening_amount NUMERIC(12, 2) NOT NULL DEFAULT 300,
    closed_at TIMESTAMP,
    closed_by_user_id BIGINT,
    counted_cash NUMERIC(12, 2),
    expected_cash NUMERIC(12, 2),
    variance NUMERIC(12, 2),
    close_notes VARCHAR(1500),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
);

CREATE INDEX IF NOT EXISTS idx_kiosk_cash_session_kiosk_status
    ON kiosk_cash_session (kiosk_location_id, status);

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS cash_session_id BIGINT REFERENCES kiosk_cash_session(id);

CREATE INDEX IF NOT EXISTS idx_kiosk_sale_cash_session
    ON kiosk_sale (cash_session_id);