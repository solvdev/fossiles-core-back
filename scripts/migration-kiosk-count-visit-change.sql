-- Cambios de fecha que hace la supervisora en «Tu calendario de la semana» (Conteo físico), con su justificación.
-- La fecha programada de la visita no se toca; manda el último cambio. Se puede correr más de una vez.

BEGIN;

CREATE TABLE IF NOT EXISTS kiosk_count_visit_change (
    id            BIGSERIAL PRIMARY KEY,
    visit_id      BIGINT       NOT NULL REFERENCES kiosk_count_visit (id) ON DELETE CASCADE,
    previous_date DATE         NOT NULL,
    new_date      DATE         NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    changed_by    BIGINT       NOT NULL,
    changed_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kiosk_count_visit_change_visit ON kiosk_count_visit_change (visit_id, id DESC);

COMMIT;
