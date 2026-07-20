-- Stock kiosko por herraje (NUEVO / VIEJO) + conteo físico por herraje + conteos internos encargada.

UPDATE kiosco_stock
SET hardware_condition = 'NUEVO'
WHERE hardware_condition IS NULL OR BTRIM(hardware_condition) = '';

ALTER TABLE kiosco_stock
    ALTER COLUMN hardware_condition SET DEFAULT 'NUEVO';

ALTER TABLE kiosco_stock
    ALTER COLUMN hardware_condition SET NOT NULL;

ALTER TABLE kiosco_stock DROP CONSTRAINT IF EXISTS uq_kiosco_stock;
ALTER TABLE kiosco_stock
    ADD CONSTRAINT uq_kiosco_stock UNIQUE (location_id, product_id, color_id, hardware_condition);

ALTER TABLE kiosco_physical_count_item
    ADD COLUMN IF NOT EXISTS hardware_location_counts_data TEXT;

COMMENT ON COLUMN kiosco_physical_count_item.hardware_location_counts_data IS
    'JSON ubicación (V1..BO) → herraje (NUEVO|VIEJO) → cantidad física.';

CREATE TABLE IF NOT EXISTS kiosco_internal_count (
    id              BIGSERIAL PRIMARY KEY,
    location_id     BIGINT NOT NULL REFERENCES location(id),
    count_date      DATE NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    saved_at        TIMESTAMP,
    CONSTRAINT uq_kiosco_internal_count_day UNIQUE (location_id, count_date)
);

CREATE TABLE IF NOT EXISTS kiosco_internal_count_item (
    id                          BIGSERIAL PRIMARY KEY,
    internal_count_id           BIGINT NOT NULL REFERENCES kiosco_internal_count(id) ON DELETE CASCADE,
    product_id                  BIGINT NOT NULL REFERENCES product(id),
    color_id                    BIGINT REFERENCES colors(id),
    counts_data                 TEXT,
    size_counts_data            TEXT,
    size_location_counts_data   TEXT,
    hardware_location_counts_data TEXT,
    observation                 TEXT,
    size_observations_data      TEXT,
    updated_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by                  BIGINT REFERENCES users(id),
    CONSTRAINT uq_kiosco_internal_count_item UNIQUE (internal_count_id, product_id, color_id)
);

CREATE INDEX IF NOT EXISTS idx_kiosco_internal_count_location
    ON kiosco_internal_count (location_id, count_date DESC);
