-- Observaciones en conteo fisico kiosco (filas con diferencia).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_physical_count_item
    ADD COLUMN IF NOT EXISTS observation TEXT;

ALTER TABLE kiosco_physical_count_item
    ADD COLUMN IF NOT EXISTS size_observations_data TEXT;

COMMENT ON COLUMN kiosco_physical_count_item.observation IS
    'Observacion para productos sin desglose por talla cuando hay diferencia de conteo.';

COMMENT ON COLUMN kiosco_physical_count_item.size_observations_data IS
    'JSON talla -> observacion (cinchos expandidos por talla con diferencia).';
