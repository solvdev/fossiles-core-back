-- Documentos por pagar: lo que se debe y no viene de una orden de compra (saldos iniciales, luz, fletes, servicios y rentas).
-- Ejecutar DESPUÉS de migration-supplier-payments.sql y ANTES de desplegar el back. Es idempotente.

-- kind: MANUAL (se captura a mano) | OPENING (saldo inicial cargado por Excel) | RENT (generado desde Rentas)
-- status: ACTIVO | ANULADO
CREATE TABLE IF NOT EXISTS payable_document (
    id              BIGSERIAL PRIMARY KEY,
    supplier_id     BIGINT REFERENCES supplier (id),      -- null si el beneficiario no es un proveedor (p. ej. un arrendador)
    payee_name      VARCHAR(200) NOT NULL,
    concept         VARCHAR(300) NOT NULL,
    document_number VARCHAR(60),
    issue_date      DATE NOT NULL,
    due_date        DATE NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    kind            VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    location_id     BIGINT REFERENCES locations (id),     -- kiosco de una renta
    period          CHAR(7),                              -- 'YYYY-MM' de una renta
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVO',
    void_reason     VARCHAR(300),
    voided_at       TIMESTAMP,
    voided_by       BIGINT,
    created_by      BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payable_document_amount CHECK (amount > 0),
    CONSTRAINT chk_payable_document_kind CHECK (kind IN ('MANUAL', 'OPENING', 'RENT')),
    CONSTRAINT chk_payable_document_status CHECK (status IN ('ACTIVO', 'ANULADO'))
);
CREATE INDEX IF NOT EXISTS idx_payable_document_due ON payable_document (due_date);
-- Una renta por kiosco y mes (las anuladas no cuentan, así se pueden volver a generar).
CREATE UNIQUE INDEX IF NOT EXISTS uq_payable_document_rent
    ON payable_document (location_id, period) WHERE kind = 'RENT' AND status = 'ACTIVO';

-- Un pago ya no exige proveedor: el beneficiario puede ser un arrendador. payee_key agrupa los pagos de un mismo
-- beneficiario: 'S:<id>' si es proveedor, 'N:<nombre normalizado>' si no.
ALTER TABLE supplier_payment ALTER COLUMN supplier_id DROP NOT NULL;
ALTER TABLE supplier_payment ADD COLUMN IF NOT EXISTS payee_key VARCHAR(220);
UPDATE supplier_payment SET payee_key = 'S:' || supplier_id WHERE payee_key IS NULL AND supplier_id IS NOT NULL;

-- Cada aplicación de un pago es a una orden de compra O a un documento por pagar.
ALTER TABLE supplier_payment_allocation ALTER COLUMN purchase_order_id DROP NOT NULL;
ALTER TABLE supplier_payment_allocation
    ADD COLUMN IF NOT EXISTS payable_document_id BIGINT REFERENCES payable_document (id);
ALTER TABLE supplier_payment_allocation DROP CONSTRAINT IF EXISTS chk_supplier_payment_alloc_target;
ALTER TABLE supplier_payment_allocation ADD CONSTRAINT chk_supplier_payment_alloc_target
    CHECK ((purchase_order_id IS NOT NULL AND payable_document_id IS NULL)
        OR (purchase_order_id IS NULL AND payable_document_id IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_supplier_payment_alloc_document ON supplier_payment_allocation (payable_document_id);
