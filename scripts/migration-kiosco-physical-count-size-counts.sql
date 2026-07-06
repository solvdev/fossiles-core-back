-- Desglose por talla en conteo fisico kiosco (cinchos).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_physical_count_item
    ADD COLUMN IF NOT EXISTS size_counts_data TEXT;

COMMENT ON COLUMN kiosco_physical_count_item.size_counts_data IS
    'JSON talla -> cantidad fisica contada (cinchos); complementa counts_data por ubicacion.';
