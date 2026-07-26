-- Inventario inicial kiosko (migración / corte desde sistema anterior).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

CREATE TABLE IF NOT EXISTS kiosco_opening_inventory (
    id              BIGSERIAL PRIMARY KEY,
    location_id     BIGINT NOT NULL REFERENCES locations (id),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    created_by      BIGINT REFERENCES users (id),
    applied_by      BIGINT REFERENCES users (id),
    applied_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_kiosco_opening_inventory_status CHECK (status IN ('DRAFT', 'APLICADO'))
);

CREATE INDEX IF NOT EXISTS idx_kiosco_opening_inventory_location
    ON kiosco_opening_inventory (location_id, status);

-- Solo un inventario inicial aplicado por kiosko.
CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosco_opening_inventory_applied_location
    ON kiosco_opening_inventory (location_id)
    WHERE status = 'APLICADO';

CREATE TABLE IF NOT EXISTS kiosco_opening_inventory_item (
    id                      BIGSERIAL PRIMARY KEY,
    opening_inventory_id    BIGINT NOT NULL REFERENCES kiosco_opening_inventory (id) ON DELETE CASCADE,
    product_id              BIGINT NOT NULL REFERENCES product (id),
    color_id                BIGINT REFERENCES colors (id),
    hardware_condition      VARCHAR(10) NOT NULL DEFAULT 'NUEVO',
    quantity                INTEGER NOT NULL DEFAULT 0,
    sizes_data              TEXT,
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by              BIGINT REFERENCES users (id),
    CONSTRAINT uq_kiosco_opening_inventory_item UNIQUE (opening_inventory_id, product_id, color_id, hardware_condition),
    CONSTRAINT chk_kiosco_opening_inventory_item_qty CHECK (quantity >= 0),
    CONSTRAINT chk_kiosco_opening_inventory_item_hw CHECK (hardware_condition IN ('NUEVO', 'VIEJO'))
);

CREATE INDEX IF NOT EXISTS idx_kiosco_opening_inventory_item_session
    ON kiosco_opening_inventory_item (opening_inventory_id);

COMMENT ON TABLE kiosco_opening_inventory IS 'Sesión de inventario inicial kiosko (migración). Aplicar crea movimientos ENTRADA (por talla en cinchos).';
COMMENT ON TABLE kiosco_opening_inventory_item IS 'Cantidades capturadas por producto/color; sizes_data JSON para cinchos FOSS.';
