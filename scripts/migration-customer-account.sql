-- Cuentas por cobrar — clientes vendedor Luis Felipe (OPV / OPC)
-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).

CREATE TABLE IF NOT EXISTS customer_account_entry (
    id                      BIGSERIAL PRIMARY KEY,
    customer_id             BIGINT NOT NULL REFERENCES customer (id),
    entry_type              VARCHAR(30) NOT NULL,
    entry_date              DATE NOT NULL,
    amount                  NUMERIC(15, 2) NOT NULL,
    reference               VARCHAR(100),
    description             TEXT,
    payment_method          VARCHAR(50),
    production_order_id     BIGINT REFERENCES production_order (id),
    product_shipment_id     BIGINT,
    vendor_shipment_number  VARCHAR(30),
    order_kind              VARCHAR(10),
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    voided_at               TIMESTAMP,
    voided_by               BIGINT,
    void_reason             TEXT,
    created_at              TIMESTAMP,
    created_by              BIGINT,
    updated_at              TIMESTAMP,
    updated_by              BIGINT,
    CONSTRAINT chk_customer_account_entry_amount CHECK (amount >= 0),
    CONSTRAINT chk_customer_account_entry_type CHECK (
        entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE')
    ),
    CONSTRAINT chk_customer_account_entry_status CHECK (status IN ('ACTIVE', 'VOID'))
);

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_customer_date
    ON customer_account_entry (customer_id, entry_date, id);

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_customer_status
    ON customer_account_entry (customer_id, status);

COMMENT ON TABLE customer_account_entry IS 'Libro de cuentas por cobrar por cliente (cargos, pagos, NC, saldo inicial)';
COMMENT ON COLUMN customer_account_entry.entry_type IS 'CHARGE | PAYMENT | CREDIT_NOTE | OPENING_BALANCE';
COMMENT ON COLUMN customer_account_entry.order_kind IS 'OPV | OPC — referencia opcional a documento LF';
