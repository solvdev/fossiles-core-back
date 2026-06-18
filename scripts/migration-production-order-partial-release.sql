-- Liberaciones parciales dentro de OP (Luis Felipe): listados de cantidades a enviar sin nueva OP.
-- PostgreSQL

CREATE TABLE IF NOT EXISTS production_order_partial_release (
    id BIGSERIAL PRIMARY KEY,
    production_order_id BIGINT NOT NULL REFERENCES production_order (id) ON DELETE CASCADE,
    sequence_num INT NOT NULL,
    label VARCHAR(120),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    notes TEXT,
    created_at TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uq_po_partial_release_seq UNIQUE (production_order_id, sequence_num)
);

CREATE INDEX IF NOT EXISTS idx_po_partial_release_order_id
    ON production_order_partial_release (production_order_id);

CREATE TABLE IF NOT EXISTS production_order_partial_release_line (
    id BIGSERIAL PRIMARY KEY,
    release_id BIGINT NOT NULL REFERENCES production_order_partial_release (id) ON DELETE CASCADE,
    production_order_item_id BIGINT NOT NULL REFERENCES production_order_item (id) ON DELETE CASCADE,
    quantity INT,
    sizes_data TEXT,
    CONSTRAINT uq_po_partial_release_line_item UNIQUE (release_id, production_order_item_id)
);

CREATE INDEX IF NOT EXISTS idx_po_partial_release_line_release_id
    ON production_order_partial_release_line (release_id);

ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS partial_release_id BIGINT NULL
        REFERENCES production_order_partial_release (id);

CREATE INDEX IF NOT EXISTS idx_product_shipment_partial_release_id
    ON product_shipment (partial_release_id);

COMMENT ON TABLE production_order_partial_release IS 'Liberación parcial (Parcial 1, 2…) dentro de la OP madre LF';
COMMENT ON COLUMN product_shipment.partial_release_id IS 'Envío generado desde una liberación parcial';
