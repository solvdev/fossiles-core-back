-- Asignación de kioskos a supervisoras, para efectos de meta/comisión agregada
-- (no restringe el acceso general de la supervisora a kioskos, ver KioskAccessHelper).

CREATE TABLE IF NOT EXISTS kiosk_supervisor_assignment (
    id                   BIGSERIAL PRIMARY KEY,
    supervisor_user_id   BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kiosk_location_id    BIGINT NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id   BIGINT NOT NULL,
    CONSTRAINT uq_kiosk_supervisor_assignment UNIQUE (supervisor_user_id, kiosk_location_id)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_supervisor_assignment_supervisor
    ON kiosk_supervisor_assignment (supervisor_user_id);
CREATE INDEX IF NOT EXISTS idx_kiosk_supervisor_assignment_kiosk
    ON kiosk_supervisor_assignment (kiosk_location_id);

COMMENT ON TABLE kiosk_supervisor_assignment IS 'Kioskos asignados a cada supervisora para efectos de meta/comisión agregada (no restringe su acceso general de kioskos).';
