-- Bitácora de intentos de certificación FEL (exitosos y fallidos)
CREATE TABLE IF NOT EXISTS tax_invoice_attempt (
    id BIGSERIAL PRIMARY KEY,
    tax_invoice_id BIGINT NOT NULL REFERENCES tax_invoice (id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    action VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_type VARCHAR(30),
    source_id BIGINT,
    internal_number VARCHAR(40),
    customer_tax_id VARCHAR(50),
    customer_name VARCHAR(200),
    address VARCHAR(500),
    phone VARCHAR(50),
    email VARCHAR(200),
    subtotal NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    fel_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    fel_transaction_id VARCHAR(80),
    fel_uuid VARCHAR(64),
    fel_serie VARCHAR(32),
    fel_numero VARCHAR(32),
    fel_error VARCHAR(4000),
    lines_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by BIGINT,
    CONSTRAINT uq_tax_invoice_attempt_number UNIQUE (tax_invoice_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_tax_invoice_attempt_invoice ON tax_invoice_attempt (tax_invoice_id);
CREATE INDEX IF NOT EXISTS idx_tax_invoice_attempt_created ON tax_invoice_attempt (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tax_invoice_attempt_status ON tax_invoice_attempt (status);
