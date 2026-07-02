-- Cierre de conteos fisicos + notificacion de diferencias sin resolver.
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_physical_count
    DROP CONSTRAINT IF EXISTS chk_kiosco_physical_count_status;

ALTER TABLE kiosco_physical_count
    ADD CONSTRAINT chk_kiosco_physical_count_status CHECK (status IN ('DRAFT', 'REVISADO', 'CERRADO'));

ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS closed_by BIGINT REFERENCES users (id),
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS max_abs_diff INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS diff_notified_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS kiosco_notification_recipient (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(200) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN kiosco_physical_count.max_abs_diff IS 'Mayor diferencia absoluta (sistema vs. fisico) entre todas las filas del conteo, recalculada en cada guardado/revision.';
COMMENT ON COLUMN kiosco_physical_count.diff_notified_at IS 'Fecha en que se envio el correo de alerta por diferencias sin resolver (evita reenvios diarios).';
COMMENT ON TABLE kiosco_notification_recipient IS 'Destinatarios (contabilidad, logistica, etc.) que reciben alertas por email de diferencias de conteo sin resolver.';
