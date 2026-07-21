-- Si ya ejecutó migration-kiosco-opening-inventory.sql sin hardware_condition.
ALTER TABLE kiosco_opening_inventory_item
    ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(10) NOT NULL DEFAULT 'NUEVO';

UPDATE kiosco_opening_inventory_item
SET hardware_condition = 'NUEVO'
WHERE hardware_condition IS NULL;

ALTER TABLE kiosco_opening_inventory_item
    DROP CONSTRAINT IF EXISTS uq_kiosco_opening_inventory_item;

ALTER TABLE kiosco_opening_inventory_item
    DROP CONSTRAINT IF EXISTS kiosco_opening_inventory_item_opening_inventory_id_product_id_color_id_key;

ALTER TABLE kiosco_opening_inventory_item
    ADD CONSTRAINT chk_kiosco_opening_inventory_item_hw
        CHECK (hardware_condition IN ('NUEVO', 'VIEJO'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosco_opening_inventory_item_hw
    ON kiosco_opening_inventory_item (opening_inventory_id, product_id, color_id, hardware_condition);
