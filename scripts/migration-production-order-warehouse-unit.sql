-- Trazabilidad por pieza en Bodega PT (recepción / despacho por unidad física)

CREATE TABLE IF NOT EXISTS production_order_warehouse_unit (
    id BIGSERIAL PRIMARY KEY,
    production_order_id BIGINT NOT NULL REFERENCES production_order (id) ON DELETE CASCADE,
    production_order_item_id BIGINT NOT NULL REFERENCES production_order_item (id) ON DELETE CASCADE,
    unit_label VARCHAR(200),
    color_id BIGINT,
    size_key VARCHAR(40) NOT NULL DEFAULT '',
    unit_seq INT NOT NULL,
    receipt_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(500),
    received_at TIMESTAMP,
    received_by BIGINT,
    shipment_ref_type VARCHAR(40),
    shipment_ref_id BIGINT,
    shipped_at TIMESTAMP,
    shipped_by BIGINT,
    created_at TIMESTAMP,
    CONSTRAINT uq_po_wh_unit_item_size_seq UNIQUE (production_order_item_id, size_key, unit_seq)
);

CREATE INDEX IF NOT EXISTS idx_po_wh_unit_order_id ON production_order_warehouse_unit (production_order_id);
CREATE INDEX IF NOT EXISTS idx_po_wh_unit_item_id ON production_order_warehouse_unit (production_order_item_id);
CREATE INDEX IF NOT EXISTS idx_po_wh_unit_receipt_status ON production_order_warehouse_unit (receipt_status);

ALTER TABLE production_order
    ADD COLUMN IF NOT EXISTS warehouse_receipt_closed_at TIMESTAMP;
