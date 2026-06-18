-- Clave legacy de cliente (ej. CB490) para búsqueda en CxC
-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS legacy_code VARCHAR(30);

CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_legacy_code
    ON customer (legacy_code)
    WHERE legacy_code IS NOT NULL AND legacy_code <> '';

CREATE INDEX IF NOT EXISTS idx_customer_legacy_code
    ON customer (legacy_code);

COMMENT ON COLUMN customer.legacy_code IS 'Clave legacy del cliente para búsqueda en cuentas por cobrar (ej. CB490)';
