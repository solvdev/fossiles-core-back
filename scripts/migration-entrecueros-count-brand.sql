-- Entre Cueros: el conteo físico de billeteras/sintéticos es por marca.
-- hardware_condition en ítems de conteo (NUEVO por defecto en kioscos normales).

ALTER TABLE kiosco_physical_count_item
    ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(40) NOT NULL DEFAULT 'NUEVO';

ALTER TABLE kiosco_internal_count_item
    ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(40) NOT NULL DEFAULT 'NUEVO';

ALTER TABLE kiosco_physical_count_item
    DROP CONSTRAINT IF EXISTS uq_kiosco_physical_count_item;

ALTER TABLE kiosco_internal_count_item
    DROP CONSTRAINT IF EXISTS uq_kiosco_internal_count_item;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosco_physical_count_item_variant
    ON kiosco_physical_count_item (
        count_id,
        product_id,
        COALESCE(color_id, -1),
        hardware_condition
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosco_internal_count_item_variant
    ON kiosco_internal_count_item (
        internal_count_id,
        product_id,
        COALESCE(color_id, -1),
        hardware_condition
    );

COMMENT ON COLUMN kiosco_physical_count_item.hardware_condition IS
    'NUEVO | VIEJO, o marca en Entre Cueros (LEVIS, NAUTICA, …).';
