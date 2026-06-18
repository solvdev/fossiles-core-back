-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).
-- Cantidad de mesas activas por fecha efectiva (centro de producción). Vigente hacia adelante.

CREATE TABLE IF NOT EXISTS production_desk_count (
    id               BIGSERIAL PRIMARY KEY,
    effective_date   DATE NOT NULL,
    num_desks        INTEGER NOT NULL,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    CONSTRAINT uq_production_desk_count_effective_date UNIQUE (effective_date)
);

CREATE INDEX IF NOT EXISTS idx_production_desk_count_effective_date
    ON production_desk_count (effective_date);

