-- Notas de Crédito (NCRE) y Débito (NDEB) FEL ligadas a una factura certificada.
-- Ejecutar en prod ANTES de reiniciar el backend (spring.jpa.hibernate.ddl-auto=validate).

ALTER TABLE tax_invoice
    ADD COLUMN IF NOT EXISTS origin_invoice_id BIGINT REFERENCES tax_invoice (id),
    ADD COLUMN IF NOT EXISTS adjustment_reason VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_tax_invoice_origin_invoice
    ON tax_invoice (origin_invoice_id);
