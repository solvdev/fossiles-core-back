-- Boletas físicas en traslados, devoluciones y cambios + autorización logística
-- Ejecutar manualmente en PostgreSQL

ALTER TABLE kiosco_movement
    ADD COLUMN IF NOT EXISTS physical_slip_number VARCHAR(60);

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_physical_slip
    ON kiosco_movement (physical_slip_number)
    WHERE physical_slip_number IS NOT NULL;

ALTER TABLE inventory_transfer
    ADD COLUMN IF NOT EXISTS physical_slip_number VARCHAR(60);

ALTER TABLE kiosk_exchange_slip
    ADD COLUMN IF NOT EXISTS given_movement_id BIGINT,
    ADD COLUMN IF NOT EXISTS authorized_by BIGINT,
    ADD COLUMN IF NOT EXISTS authorized_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_pending_auth
    ON kiosk_exchange_slip (status)
    WHERE status = 'PENDING_AUTHORIZATION';

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.CAMBIOS.AUTORIZAR.VER', 'Ver solicitudes de cambio pendientes de autorización', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.CAMBIOS.AUTORIZAR.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR', 'Autorizar o rechazar cambios sin diferencia de precio', 'KIOSCOS', 'APROBAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR');
