-- CxC: descarga (cobro), conceptos legacy y devoluciones
-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS movement_concept_code VARCHAR(10);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS receipt_number VARCHAR(50);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS collection_date DATE;

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS payment_discount_amount NUMERIC(15, 2);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS payment_discount_percent NUMERIC(7, 4);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS gross_collected_amount NUMERIC(15, 2);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS applied_to_entry_id BIGINT REFERENCES customer_account_entry (id);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(50);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS document_number VARCHAR(50);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS return_voucher_number VARCHAR(50);

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS return_date DATE;

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS return_reason TEXT;

ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_type;

ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_type CHECK (
        entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN')
    );

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_applied_to
    ON customer_account_entry (applied_to_entry_id)
    WHERE applied_to_entry_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_receipt
    ON customer_account_entry (customer_id, receipt_number)
    WHERE receipt_number IS NOT NULL;

COMMENT ON COLUMN customer_account_entry.movement_concept_code IS 'Concepto legacy: 1=factura, 2=NC, 3=cheque, 4=efectivo, 5=anticipo, 11=descarga';
COMMENT ON COLUMN customer_account_entry.applied_to_entry_id IS 'Cargo CHARGE al que aplica PAYMENT o RETURN';
COMMENT ON COLUMN customer_account_entry.return_voucher_number IS 'No. boleta de devolución';
