-- CxC: vínculos a parcial/envío e índices OPV/OPC
-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).

ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS partial_release_id BIGINT REFERENCES production_order_partial_release (id);

ALTER TABLE customer_account_entry
    ADD CONSTRAINT fk_customer_account_entry_product_shipment
        FOREIGN KEY (product_shipment_id) REFERENCES product_shipment (id);

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_partial_release
    ON customer_account_entry (partial_release_id)
    WHERE partial_release_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_product_shipment
    ON customer_account_entry (product_shipment_id)
    WHERE product_shipment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_account_entry_order_kind
    ON customer_account_entry (customer_id, order_kind, status);

COMMENT ON COLUMN customer_account_entry.partial_release_id IS 'Liberación parcial LF vinculada al cargo';
