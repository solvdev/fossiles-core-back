-- Correr a mano en PostgreSQL ANTES de desplegar el back (JPA ddl-auto=validate).
-- Mesas especiales del centro de producción (p. ej. mesa 12 = maquila).
-- Van aparte de las mesas de planta 1..N (production_desk_count): el reparto automático
-- nunca les manda tareas y su carga no cuenta en el cupo de la planta.
-- El encargado se guarda igual que el de cualquier mesa, en production_desk_supervisor.

CREATE TABLE IF NOT EXISTS production_special_desk (
    id          BIGSERIAL PRIMARY KEY,
    desk        INTEGER      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(30)  NOT NULL DEFAULT 'MAQUILA',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    CONSTRAINT uq_production_special_desk_desk UNIQUE (desk),
    CONSTRAINT ck_production_special_desk_range CHECK (desk BETWEEN 1 AND 32)
);

-- Mesa 12 de maquila.
INSERT INTO production_special_desk (desk, name, kind, active, created_at, updated_at)
VALUES (12, 'Maquila', 'MAQUILA', TRUE, NOW(), NOW())
ON CONFLICT (desk) DO NOTHING;
