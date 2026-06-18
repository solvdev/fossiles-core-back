-- Envíos de producto ligados a OP (OPI/OPCK) sin distribución.
-- PostgreSQL

ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS production_order_id BIGINT NULL REFERENCES production_order (id);

ALTER TABLE product_shipment
    ALTER COLUMN distribution_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_shipment_production_order_id ON product_shipment (production_order_id);

COMMENT ON COLUMN product_shipment.production_order_id IS 'OP INTERNA/CLIENTE_KIOSKO cuando el envío no pertenece a una product_distribution';
