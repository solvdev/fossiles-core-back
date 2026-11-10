-- Paso a prod del Centro de Producción (release/centro-produccion, 2026-10-23).
-- Correr en RDS (fossilesgt) ANTES de mergear el back a main: ddl-auto=validate no arranca sin esto.
-- Junta, en orden, scripts/migration-task-delay-commission.sql y scripts/migration-production-order-group.sql.
-- Todo es IF NOT EXISTS: se puede volver a correr sin daño. Rollback: revertir el binario; lo creado es inofensivo.

BEGIN;

ALTER TABLE task
  ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS expected_minutes INTEGER,
  ADD COLUMN IF NOT EXISTS delay_minutes INTEGER,
  ADD COLUMN IF NOT EXISTS delayed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS commission_eligible BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS delay_forgiven_by BIGINT,
  ADD COLUMN IF NOT EXISTS delay_forgiven_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS delay_forgiven_reason VARCHAR(500);

CREATE TABLE IF NOT EXISTS task_event (
  id BIGSERIAL PRIMARY KEY,
  task_id BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  reason VARCHAR(500),
  detail TEXT,
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_task_event_task ON task_event(task_id, created_at);

CREATE TABLE IF NOT EXISTS production_order_group (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  family VARCHAR(10) NOT NULL,               -- OPV | OPC
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISSOLVED
  observations VARCHAR(500),
  member_codes VARCHAR(1000),                -- códigos de las OP al crearla (queda al desagrupar)
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  dissolved_by BIGINT,
  dissolved_at TIMESTAMP
);

-- Una OP está a lo más en una consolidada activa: al desagrupar se borran sus filas.
CREATE TABLE IF NOT EXISTS production_order_group_member (
  id BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES production_order_group(id),
  production_order_id BIGINT NOT NULL UNIQUE REFERENCES production_order(id),
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_po_group_member_group ON production_order_group_member(group_id);

COMMIT;
