-- Desglose por talla en inventario kiosko (mismo formato JSON que product_inventory_location.sizes_data).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE kiosco_stock
    ADD COLUMN IF NOT EXISTS sizes_data TEXT;

