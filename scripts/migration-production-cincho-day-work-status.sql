-- Estado de trabajo por línea en mesa cinchos (sin tiempos de tarea).
-- Valores: PENDING, IN_PROGRESS, COMPLETED

ALTER TABLE production_cincho_day_status
    ADD COLUMN IF NOT EXISTS work_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

UPDATE production_cincho_day_status
SET work_status = 'COMPLETED'
WHERE work_status = 'PENDING' AND delivered = true;
