-- Asociar devoluciones / reintegros a un conteo físico para que cuenten en Salidas del periodo.

ALTER TABLE kiosco_movement
    ADD COLUMN IF NOT EXISTS physical_count_id BIGINT;

ALTER TABLE kiosk_exchange_slip
    ADD COLUMN IF NOT EXISTS physical_count_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_physical_count
    ON kiosco_movement (physical_count_id)
    WHERE physical_count_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_physical_count
    ON kiosk_exchange_slip (physical_count_id)
    WHERE physical_count_id IS NOT NULL;
