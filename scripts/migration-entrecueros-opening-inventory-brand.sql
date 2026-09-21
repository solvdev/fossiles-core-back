-- Entre Cueros (location 42): la dimensión de stock de billeteras/sintéticos es la marca,
-- no herraje NUEVO/VIEJO. Se guarda en hardware_condition.

ALTER TABLE kiosco_opening_inventory_item
    DROP CONSTRAINT IF EXISTS chk_kiosco_opening_inventory_item_hw;

ALTER TABLE kiosco_opening_inventory_item
    ALTER COLUMN hardware_condition TYPE VARCHAR(40);

ALTER TABLE kiosco_stock
    ALTER COLUMN hardware_condition TYPE VARCHAR(40);

COMMENT ON COLUMN kiosco_opening_inventory_item.hardware_condition IS
    'NUEVO | VIEJO (herraje) o marca en Entre Cueros (LEVIS, NAUTICA, …).';

COMMENT ON COLUMN kiosco_stock.hardware_condition IS
    'NUEVO | VIEJO (herraje) o marca en Entre Cueros (LEVIS, NAUTICA, …).';
