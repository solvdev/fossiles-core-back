-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).
-- Encargado por mesa y fecha de trabajo (centro de producción).

CREATE TABLE IF NOT EXISTS production_desk_supervisor (
    id               BIGSERIAL PRIMARY KEY,
    desk             INTEGER NOT NULL,
    effective_date   DATE NOT NULL,
    supervisor_name  VARCHAR(200),
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    CONSTRAINT uq_production_desk_supervisor_desk_date UNIQUE (desk, effective_date)
);

CREATE INDEX IF NOT EXISTS idx_production_desk_supervisor_effective_date
    ON production_desk_supervisor (effective_date);
