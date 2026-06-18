-- Tabla general de facturas electrónicas (FEL)
CREATE TABLE IF NOT EXISTS tax_invoice (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT,
    document_type VARCHAR(10) NOT NULL DEFAULT 'FACT',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    customer_tax_id VARCHAR(50),
    customer_name VARCHAR(200),
    address VARCHAR(500),
    phone VARCHAR(50),
    email VARCHAR(200),
    subtotal NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    fel_uuid VARCHAR(64),
    fel_serie VARCHAR(32),
    fel_numero VARCHAR(32),
    fel_error VARCHAR(4000),
    fel_certified_at TIMESTAMP,
    fel_transaction_id VARCHAR(80),
    internal_number VARCHAR(40),
    issued_at TIMESTAMP,
    notes VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by BIGINT,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tax_invoice_source ON tax_invoice (source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_tax_invoice_status ON tax_invoice (status);
CREATE INDEX IF NOT EXISTS idx_tax_invoice_issued_at ON tax_invoice (issued_at);
CREATE INDEX IF NOT EXISTS idx_tax_invoice_customer_tax_id ON tax_invoice (customer_tax_id);

CREATE TABLE IF NOT EXISTS tax_invoice_line (
    id BIGSERIAL PRIMARY KEY,
    tax_invoice_id BIGINT NOT NULL REFERENCES tax_invoice (id) ON DELETE CASCADE,
    line_number INT NOT NULL DEFAULT 1,
    description VARCHAR(500) NOT NULL,
    quantity NUMERIC(12, 3) NOT NULL DEFAULT 1,
    unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
    gravable_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tax_invoice_line_invoice ON tax_invoice_line (tax_invoice_id);

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS invoice_id BIGINT REFERENCES tax_invoice (id);

ALTER TABLE online_sale
    ADD COLUMN IF NOT EXISTS invoice_id BIGINT REFERENCES tax_invoice (id);

-- Backfill desde ventas POS con datos FEL existentes
INSERT INTO tax_invoice (
    source_type,
    source_id,
    document_type,
    status,
    customer_tax_id,
    customer_name,
    address,
    phone,
    email,
    subtotal,
    discount_amount,
    tax_amount,
    total_amount,
    fel_uuid,
    fel_serie,
    fel_numero,
    fel_error,
    fel_certified_at,
    fel_transaction_id,
    internal_number,
    issued_at,
    created_at,
    created_by
)
SELECT
    'KIOSK_SALE',
    ks.id,
    'FACT',
    COALESCE(ks.fel_status, 'SKIPPED'),
    ks.customer_tax_id,
    ks.customer_name,
    ks.address,
    ks.phone,
    ks.email,
    COALESCE(ks.subtotal, 0),
    COALESCE(ks.discount_amount, 0),
    0,
    COALESCE(ks.total_amount, 0),
    ks.fel_uuid,
    ks.fel_serie,
    ks.fel_numero,
    ks.fel_error,
    ks.fel_certified_at,
    ks.sale_number,
    ks.sale_number,
    COALESCE(ks.fel_certified_at, ks.sold_at, ks.created_at),
    COALESCE(ks.created_at, NOW()),
    ks.created_by
FROM kiosk_sale ks
WHERE ks.invoice_id IS NULL
  AND (ks.fel_uuid IS NOT NULL OR ks.fel_status IS NOT NULL);

UPDATE kiosk_sale ks
SET invoice_id = ti.id
FROM tax_invoice ti
WHERE ti.source_type = 'KIOSK_SALE'
  AND ti.source_id = ks.id
  AND ks.invoice_id IS NULL;

-- Líneas de backfill (una línea resumen por factura POS migrada)
INSERT INTO tax_invoice_line (
    tax_invoice_id,
    line_number,
    description,
    quantity,
    unit_price,
    line_total,
    gravable_amount,
    tax_amount
)
SELECT
    ti.id,
    1,
    'Venta POS ' || COALESCE(ks.sale_number, ks.id::TEXT),
    1,
    COALESCE(ks.total_amount, 0),
    COALESCE(ks.total_amount, 0),
    0,
    0
FROM tax_invoice ti
JOIN kiosk_sale ks ON ks.id = ti.source_id AND ti.source_type = 'KIOSK_SALE'
WHERE NOT EXISTS (
    SELECT 1 FROM tax_invoice_line til WHERE til.tax_invoice_id = ti.id
);
