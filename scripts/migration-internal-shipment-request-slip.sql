-- Talonario de boletas de solicitud de envío interno (BLS-nnnnn)
-- PostgreSQL

ALTER TABLE internal_shipment_request
    ADD COLUMN IF NOT EXISTS slip_number VARCHAR(50) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_internal_shipment_request_slip_number
    ON internal_shipment_request (slip_number)
    WHERE slip_number IS NOT NULL;

CREATE TABLE IF NOT EXISTS internal_shipment_request_slip (
    id BIGSERIAL PRIMARY KEY,
    slip_number VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL DEFAULT 'PRINTED',
    printed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    printed_by BIGINT,
    request_id BIGINT REFERENCES internal_shipment_request (id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_slip_status
    ON internal_shipment_request_slip (status);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_slip_number_lookup
    ON internal_shipment_request_slip (slip_number);

COMMENT ON TABLE internal_shipment_request_slip IS 'Correlativos de talonarios de boletas de solicitud de envío interno (BLS-nnnnn)';
COMMENT ON COLUMN internal_shipment_request_slip.status IS 'PRINTED, USED, VOIDED';
