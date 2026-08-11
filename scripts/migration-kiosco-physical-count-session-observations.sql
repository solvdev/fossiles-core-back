-- Observaciones generales de la sesión de conteo físico (encabezado / Excel).
-- Distinto de notes (áreas a corregir en revisión) y de observation por ítem.
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS observations TEXT;

COMMENT ON COLUMN kiosco_physical_count.observations IS
    'Observaciones generales del conteo (hallazgos durante el trabajo); visible en UI y encabezado de Excel/PDF.';
