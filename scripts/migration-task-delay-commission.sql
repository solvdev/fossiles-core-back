-- Armar el día / flujo de tareas: pausa, historial y atraso con comisión.
-- Correr ANTES de desplegar el binario (ddl-auto=validate no arranca sin las columnas).
-- Las tareas existentes quedan comisionables y sin atraso retroactivo.
-- Rollback: revertir el binario; las columnas y la tabla sobrantes son inofensivas.

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
