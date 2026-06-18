-- Solicitudes de envío interno ENVI (aprobación Contabilidad antes de generar número ENVI)
-- PostgreSQL

CREATE TABLE IF NOT EXISTS internal_shipment_request (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    request_type VARCHAR(30) NOT NULL,
    recipient_name VARCHAR(200) NOT NULL,
    recipient_phone VARCHAR(50),
    recipient_tax_id VARCHAR(50),
    notes TEXT,
    document_date VARCHAR(10),
    requested_by BIGINT,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    rejection_reason VARCHAR(1000),
    product_shipment_id BIGINT REFERENCES product_shipment (id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_status
    ON internal_shipment_request (status);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_requested_at
    ON internal_shipment_request (requested_at DESC);

CREATE TABLE IF NOT EXISTS internal_shipment_request_line (
    id BIGSERIAL PRIMARY KEY,
    request_id BIGINT NOT NULL REFERENCES internal_shipment_request (id) ON DELETE CASCADE,
    line_order INT NOT NULL DEFAULT 1,
    product_id BIGINT NOT NULL REFERENCES product (id),
    color_id BIGINT REFERENCES color (id),
    size VARCHAR(50),
    quantity NUMERIC(12, 3) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_line_request_id
    ON internal_shipment_request_line (request_id);

COMMENT ON TABLE internal_shipment_request IS 'Solicitud de envío interno ENVI; al aprobar se genera product_shipment con ENVI-nnnnn';
COMMENT ON COLUMN internal_shipment_request.request_type IS 'PLANILLA o DEFECTOS (50% descuento en documento)';
COMMENT ON COLUMN internal_shipment_request.product_shipment_id IS 'Envío generado tras aprobación';
