-- Inventario productos: desglose por talla para cinchos FOSS (product_inventory_location.sizes_data)

ALTER TABLE product_inventory_location
    ADD COLUMN IF NOT EXISTS sizes_data TEXT NULL;

-- Ajustes: auditoría de desglose por talla (opcional en request)
ALTER TABLE inventory_adjustment
    ADD COLUMN IF NOT EXISTS system_sizes_data TEXT NULL;

ALTER TABLE inventory_adjustment
    ADD COLUMN IF NOT EXISTS physical_sizes_data TEXT NULL;
