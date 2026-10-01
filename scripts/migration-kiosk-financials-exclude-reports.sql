-- ---------------------------------------------------------------------------
-- Finanzas por kiosco: sitios externos fuera de los reportes (p. ej. Entrecueros Pueblito).
-- Un sitio con exclude_from_reports = TRUE no aparece en P&L, matriz diaria, comparativo, metas,
-- completitud ni en la pantalla de costos. No se borra ningun dato: se puede revertir con
--   UPDATE kiosk_site SET exclude_from_reports = FALSE WHERE id = <id>;
-- Ejecutar a mano ANTES de desplegar el backend (la entidad lee la columna). Es idempotente.
-- ---------------------------------------------------------------------------
ALTER TABLE kiosk_site ADD COLUMN IF NOT EXISTS exclude_from_reports BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE kiosk_site
SET exclude_from_reports = TRUE
WHERE UPPER(name) LIKE '%PUEBLITO%'
   OR location_id IN (SELECT id FROM locations WHERE UPPER(name) LIKE '%PUEBLITO%');

-- Verificacion: debe listar Entrecueros Pueblito (y solo los externos que quieras fuera)
SELECT id, name, location_id, exclude_from_reports FROM kiosk_site WHERE exclude_from_reports;
