-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).
-- Entregado por línea cincho OPL/OPCK y fecha de trabajo (centro de producción).

CREATE TABLE IF NOT EXISTS production_cincho_day_status (
    id                        BIGSERIAL PRIMARY KEY,
    work_date                 DATE NOT NULL,
    production_order_id       BIGINT NOT NULL,
    production_order_item_id  BIGINT NOT NULL,
    delivered                 BOOLEAN NOT NULL DEFAULT false,
    delivered_at              TIMESTAMP,
    delivered_by              BIGINT,
    created_at                TIMESTAMP,
    updated_at                TIMESTAMP,
    CONSTRAINT uq_production_cincho_day_status UNIQUE (work_date, production_order_id, production_order_item_id)
);

CREATE INDEX IF NOT EXISTS idx_production_cincho_day_status_work_date
    ON production_cincho_day_status (work_date);
