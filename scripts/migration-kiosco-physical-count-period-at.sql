-- Periodo de conteo fisico con fecha+hora (wall-clock Guatemala).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS period_from_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS period_to_at TIMESTAMP;

-- Backfill: inclusive start 00:00, inclusive end 23:59:59 del dia period_to
UPDATE kiosco_physical_count
SET
    period_from_at = COALESCE(period_from_at, period_from::timestamp),
    period_to_at = COALESCE(
        period_to_at,
        (period_to::timestamp + INTERVAL '1 day' - INTERVAL '1 second')
    )
WHERE period_from_at IS NULL OR period_to_at IS NULL;

ALTER TABLE kiosco_physical_count
    ALTER COLUMN period_from_at SET NOT NULL,
    ALTER COLUMN period_to_at SET NOT NULL;

COMMENT ON COLUMN kiosco_physical_count.period_from_at IS
    'Inicio inclusive del periodo (LocalDateTime wall-clock Guatemala).';
COMMENT ON COLUMN kiosco_physical_count.period_to_at IS
    'Fin inclusive del periodo (LocalDateTime wall-clock Guatemala).';
