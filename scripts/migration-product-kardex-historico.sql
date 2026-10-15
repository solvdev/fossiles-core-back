-- Histórico del kardex de producto: movimientos archivados por una limpieza de inventario.
-- Solo consulta (pantalla "Kardex histórico"). No participa en saldos ni en cálculos.
-- Correr ANTES de desplegar el back (ddl-auto=validate exige que la tabla exista).

CREATE TABLE IF NOT EXISTS product_inventory_kardex_historico (
    id                BIGINT PRIMARY KEY,          -- mismo id que tenía en product_inventory_kardex
    product_id        BIGINT        NOT NULL,
    location_id       BIGINT        NOT NULL,
    color_id          BIGINT,
    size_label        VARCHAR(50),
    movement_type     VARCHAR(50)   NOT NULL,
    quantity          NUMERIC(12, 3) NOT NULL,
    quantity_before   NUMERIC(12, 3),
    quantity_after    NUMERIC(12, 3),
    unit_cost         NUMERIC(12, 2),
    total_cost        NUMERIC(15, 2),
    reference_type    VARCHAR(50),
    reference_id      BIGINT,
    reference_number  VARCHAR(100),
    reference_line_id BIGINT,
    description       VARCHAR(500),
    movement_date     TIMESTAMP     NOT NULL,
    created_at        TIMESTAMP,
    created_by        BIGINT,
    archive_reference VARCHAR(100)  NOT NULL,      -- p.ej. LIMPIEZA-20261020
    archived_at       TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pik_hist_location_date
    ON product_inventory_kardex_historico (location_id, movement_date DESC);
CREATE INDEX IF NOT EXISTS idx_pik_hist_product
    ON product_inventory_kardex_historico (product_id);
CREATE INDEX IF NOT EXISTS idx_pik_hist_archive
    ON product_inventory_kardex_historico (archive_reference);
