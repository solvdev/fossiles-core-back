-- Precios especiales por talla (Luis Felipe / OPC cinchos) y precio en línea de envío
ALTER TABLE production_order_item
    ADD COLUMN IF NOT EXISTS unit_prices_json TEXT;

ALTER TABLE product_shipment_detail
    ADD COLUMN IF NOT EXISTS unit_price NUMERIC(12, 2);

COMMENT ON COLUMN production_order_item.unit_prices_json IS
    'JSON de precios unitarios por talla, ej. {"46":125,"48":125,"30":80}. Fallback: unit_price.';

COMMENT ON COLUMN product_shipment_detail.unit_price IS
    'Precio unitario de la línea del envío (override por talla/cliente).';
