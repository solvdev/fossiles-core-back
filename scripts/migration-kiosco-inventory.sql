-- Inventario dedicado para kioskos (separado de product_inventory_location).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

CREATE TABLE IF NOT EXISTS kiosco_stock (
    id BIGSERIAL PRIMARY KEY,
    location_id BIGINT NOT NULL REFERENCES locations (id),
    product_id BIGINT NOT NULL REFERENCES product (id),
    color_id BIGINT REFERENCES colors (id),
    current_stock INTEGER NOT NULL DEFAULT 0,
    minimum_stock INTEGER NOT NULL DEFAULT 0,
    last_updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by BIGINT REFERENCES users (id),
    updated_by BIGINT REFERENCES users (id),
    CONSTRAINT uq_kiosco_stock UNIQUE (location_id, product_id, color_id),
    CONSTRAINT chk_kiosco_stock_current_non_negative CHECK (current_stock >= 0),
    CONSTRAINT chk_kiosco_stock_minimum_non_negative CHECK (minimum_stock >= 0)
);

CREATE INDEX IF NOT EXISTS idx_kiosco_stock_location_product_color
    ON kiosco_stock (location_id, product_id, color_id);

CREATE INDEX IF NOT EXISTS idx_kiosco_stock_low_stock
    ON kiosco_stock (location_id, current_stock, minimum_stock);

CREATE TABLE IF NOT EXISTS kiosco_movement (
    id BIGSERIAL PRIMARY KEY,
    kiosco_stock_id BIGINT NOT NULL REFERENCES kiosco_stock (id),
    movement_type VARCHAR(40) NOT NULL,
    quantity INTEGER NOT NULL,
    stock_before INTEGER NOT NULL,
    stock_after INTEGER NOT NULL,
    reference_id BIGINT,
    reason TEXT,
    affects_stock BOOLEAN NOT NULL DEFAULT TRUE,
    user_id BIGINT NOT NULL REFERENCES users (id),
    origin_location_id BIGINT REFERENCES locations (id),
    destination_location_id BIGINT REFERENCES locations (id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_kiosco_movement_type CHECK (
        movement_type IN (
            'ENTRADA',
            'VENTA',
            'DEVOLUCION_DEPOSITO',
            'DEVOLUCION_CLIENTE',
            'TRASLADO_SALIDA',
            'TRASLADO_ENTRADA',
            'MERMA',
            'AJUSTE',
            'ANULACION'
        )
    ),
    CONSTRAINT chk_kiosco_movement_quantity CHECK (
        (movement_type = 'AJUSTE' AND quantity >= 0)
        OR (movement_type <> 'AJUSTE' AND quantity > 0)
    ),
    CONSTRAINT chk_kiosco_movement_stock_after_non_negative CHECK (stock_after >= 0),
    CONSTRAINT chk_kiosco_movement_reason_required CHECK (
        movement_type NOT IN ('MERMA', 'AJUSTE', 'ANULACION')
        OR NULLIF(BTRIM(COALESCE(reason, '')), '') IS NOT NULL
    ),
    CONSTRAINT chk_kiosco_movement_no_effect_stock_consistency CHECK (
        affects_stock = TRUE OR stock_before = stock_after
    )
);

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_stock_created_desc
    ON kiosco_movement (kiosco_stock_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_reference
    ON kiosco_movement (reference_id, movement_type);

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_type_created_desc
    ON kiosco_movement (movement_type, created_at DESC);

-- Append-only: no permitir UPDATE/DELETE en el log de movimientos.
CREATE OR REPLACE FUNCTION prevent_kiosco_movement_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'kiosco_movement is append-only; UPDATE/DELETE are not allowed';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kiosco_movement_no_update ON kiosco_movement;
CREATE TRIGGER trg_kiosco_movement_no_update
BEFORE UPDATE ON kiosco_movement
FOR EACH ROW
EXECUTE FUNCTION prevent_kiosco_movement_mutation();

DROP TRIGGER IF EXISTS trg_kiosco_movement_no_delete ON kiosco_movement;
CREATE TRIGGER trg_kiosco_movement_no_delete
BEFORE DELETE ON kiosco_movement
FOR EACH ROW
EXECUTE FUNCTION prevent_kiosco_movement_mutation();

COMMENT ON TABLE kiosco_stock IS 'Stock actual por kiosko, producto y color (inventario dedicado kiosko).';
COMMENT ON TABLE kiosco_movement IS 'Log inmutable (append-only) de movimientos de inventario kiosko.';
