-- Clasificación niño para cinchos + herraje (nuevo/viejo) en envío y stock kiosco.

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS cincho_for_kids BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN product.cincho_for_kids IS 'true = cincho de niño; solo aplica cuando cincho_type está definido';

ALTER TABLE product_shipment_detail
    ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(20);

COMMENT ON COLUMN product_shipment_detail.hardware_condition IS 'NUEVO | VIEJO — herraje; se propaga al stock del kiosco al recibir';

-- Reemplazar unique (shipment, product, color, size) por una que incluya herraje.
DO $$
DECLARE
    cname text;
BEGIN
    FOR cname IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'public'
          AND rel.relname = 'product_shipment_detail'
          AND con.contype = 'u'
          AND pg_get_constraintdef(con.oid) ILIKE '%shipment_id%'
          AND pg_get_constraintdef(con.oid) ILIKE '%product_id%'
          AND pg_get_constraintdef(con.oid) ILIKE '%color_id%'
          AND pg_get_constraintdef(con.oid) ILIKE '%size_label%'
          AND pg_get_constraintdef(con.oid) NOT ILIKE '%hardware_condition%'
    LOOP
        EXECUTE format('ALTER TABLE product_shipment_detail DROP CONSTRAINT IF EXISTS %I', cname);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_shipment_detail_product_color_size_hw'
    ) THEN
        ALTER TABLE product_shipment_detail
            ADD CONSTRAINT uq_shipment_detail_product_color_size_hw
            UNIQUE (shipment_id, product_id, color_id, size_label, hardware_condition);
    END IF;
END $$;

ALTER TABLE kiosco_stock
    ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(20);

COMMENT ON COLUMN kiosco_stock.hardware_condition IS 'NUEVO | VIEJO — herraje del producto en el kiosko (último valor recibido por distribución)';
