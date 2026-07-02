-- Conteo fisico de inventario kiosco (cruzado contra el Kardex existente).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

CREATE TABLE IF NOT EXISTS kiosco_physical_count (
    id              BIGSERIAL PRIMARY KEY,
    location_id     BIGINT NOT NULL REFERENCES locations (id),
    period_from     DATE NOT NULL,
    period_to       DATE NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    generated_by    BIGINT NOT NULL REFERENCES users (id),
    generated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_by     BIGINT REFERENCES users (id),
    reviewed_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_kiosco_physical_count_status CHECK (status IN ('DRAFT', 'REVISADO')),
    CONSTRAINT chk_kiosco_physical_count_period CHECK (period_from <= period_to)
);

CREATE INDEX IF NOT EXISTS idx_kiosco_physical_count_location
    ON kiosco_physical_count (location_id, period_from, period_to);

CREATE TABLE IF NOT EXISTS kiosco_physical_count_item (
    id              BIGSERIAL PRIMARY KEY,
    count_id        BIGINT NOT NULL REFERENCES kiosco_physical_count (id) ON DELETE CASCADE,
    product_id      BIGINT NOT NULL REFERENCES product (id),
    color_id        BIGINT REFERENCES colors (id),
    counts_data     TEXT,
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by      BIGINT REFERENCES users (id),
    CONSTRAINT uq_kiosco_physical_count_item UNIQUE (count_id, product_id, color_id)
);

CREATE INDEX IF NOT EXISTS idx_kiosco_physical_count_item_count
    ON kiosco_physical_count_item (count_id);

COMMENT ON TABLE kiosco_physical_count IS 'Sesion de conteo fisico de inventario kiosco por periodo (editable hasta y despues de revisado).';
COMMENT ON TABLE kiosco_physical_count_item IS 'Conteo fisico por producto/color: JSON de ubicacion (V1..V7, E, BO) -> cantidad.';
