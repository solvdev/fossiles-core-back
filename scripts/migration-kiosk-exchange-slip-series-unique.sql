-- Unicidad de boleta de cambio por serie del kiosko (no global).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosk_exchange_slip
    ADD COLUMN IF NOT EXISTS series_code VARCHAR(20);

-- Backfill: serie del kiosko o K{locationId} si no hay serie.
UPDATE kiosk_exchange_slip s
SET series_code = COALESCE(
    NULLIF(UPPER(TRIM(l.internal_series_code)), ''),
    'K' || s.kiosk_location_id::text
)
FROM locations l
WHERE l.id = s.kiosk_location_id
  AND (s.series_code IS NULL OR TRIM(s.series_code) = '');

UPDATE kiosk_exchange_slip
SET series_code = 'K' || kiosk_location_id::text
WHERE series_code IS NULL OR TRIM(series_code) = '';

ALTER TABLE kiosk_exchange_slip
    ALTER COLUMN series_code SET NOT NULL;

-- Quitar UNIQUE global sobre slip_number (nombre puede variar).
ALTER TABLE kiosk_exchange_slip
    DROP CONSTRAINT IF EXISTS kiosk_exchange_slip_slip_number_key;

DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'kiosk_exchange_slip'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) ILIKE '%slip_number%'
      AND pg_get_constraintdef(oid) NOT ILIKE '%series_code%'
    LIMIT 1;
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE kiosk_exchange_slip DROP CONSTRAINT %I', cname);
    END IF;
END $$;

DROP INDEX IF EXISTS kiosk_exchange_slip_slip_number_key;
DROP INDEX IF EXISTS uq_kiosk_exchange_slip_slip_number;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosk_exchange_slip_series_number
    ON kiosk_exchange_slip (series_code, slip_number);

COMMENT ON COLUMN kiosk_exchange_slip.series_code IS
    'Serie del kiosko (locations.internal_series_code) o K{locationId}; unicidad con slip_number.';
