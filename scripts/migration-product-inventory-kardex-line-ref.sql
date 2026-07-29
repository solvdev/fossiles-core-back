-- Kardex de producto: talla y línea de documento por movimiento.
--
-- Motivo: el candado de idempotencia era (reference_type, reference_id, movement_type,
-- product_id, location_id, color_id). Como no incluía la talla ni la línea del documento,
-- un envío o venta con dos tallas del mismo producto+color solo descargaba la primera:
-- la segunda se consideraba "ya aplicada" y se saltaba en silencio.
--
-- size_label       -> talla del movimiento (cinchos FOSS con desglose por talla).
-- reference_line_id-> id de la línea que originó el movimiento
--                     (product_shipment_detail.id, online_sale_item.id, envio_detalle.id).
--
-- Ejecutar ANTES de desplegar el backend: spring.jpa.hibernate.ddl-auto=validate.

ALTER TABLE product_inventory_kardex
    ADD COLUMN IF NOT EXISTS size_label varchar(50);

ALTER TABLE product_inventory_kardex
    ADD COLUMN IF NOT EXISTS reference_line_id bigint;

COMMENT ON COLUMN product_inventory_kardex.size_label IS
    'Talla del movimiento (cinchos FOSS con sizes_data). Null si el producto no maneja tallas.';
COMMENT ON COLUMN product_inventory_kardex.reference_line_id IS
    'Id de la línea del documento que originó el movimiento. Null en movimientos previos a la migración.';

-- Índice del candado de idempotencia por línea (lo consulta cada descarga).
CREATE INDEX IF NOT EXISTS idx_product_inventory_kardex_reference_line
    ON product_inventory_kardex (reference_type, reference_id, movement_type, product_id, reference_line_id);

-- Índice de apoyo para el neteo por ubicación (descarga + reversión).
CREATE INDEX IF NOT EXISTS idx_product_inventory_kardex_reference_location
    ON product_inventory_kardex (reference_type, reference_id, product_id, location_id);

-- Backfill de talla en las salidas de envío ya registradas, cuando la línea del envío
-- es la única del par producto+color (si hay varias tallas no se puede desambiguar y
-- se deja en null: esos son precisamente los casos afectados por el defecto).
UPDATE product_inventory_kardex k
SET size_label = d.size_label
FROM product_shipment_detail d
WHERE k.reference_type = 'SHIPMENT'
  AND k.reference_id = d.shipment_id
  AND k.product_id = d.product_id
  AND ((k.color_id IS NULL AND d.color_id IS NULL) OR k.color_id = d.color_id)
  AND k.size_label IS NULL
  AND d.size_label IS NOT NULL
  AND (
    SELECT count(*) FROM product_shipment_detail d2
    WHERE d2.shipment_id = d.shipment_id
      AND d2.product_id = d.product_id
      AND ((d2.color_id IS NULL AND d.color_id IS NULL) OR d2.color_id = d.color_id)
  ) = 1;

UPDATE product_inventory_kardex k
SET reference_line_id = d.id
FROM product_shipment_detail d
WHERE k.reference_type = 'SHIPMENT'
  AND k.reference_id = d.shipment_id
  AND k.product_id = d.product_id
  AND ((k.color_id IS NULL AND d.color_id IS NULL) OR k.color_id = d.color_id)
  AND k.reference_line_id IS NULL
  AND (
    SELECT count(*) FROM product_shipment_detail d2
    WHERE d2.shipment_id = d.shipment_id
      AND d2.product_id = d.product_id
      AND ((d2.color_id IS NULL AND d.color_id IS NULL) OR d2.color_id = d.color_id)
  ) = 1;
