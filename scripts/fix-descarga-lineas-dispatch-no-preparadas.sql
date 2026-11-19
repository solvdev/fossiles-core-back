-- (Origen: Bodega PT primero y, si no tiene, Devoluciones.) Descarga las líneas «despachar desde inventario» (ruta DISPATCH) de ventas en línea YA ENVIADAS que nunca
-- salieron de Bodega PT. Caso de 2026-11-19: ENVL-01691 (B-19 color 3, línea 3133) y ENVL-01689 (FOSS-20 color 3,
-- línea 3142). El código nuevo (CustomerShipmentDispatchService.deductUnpreparedDispatchLines) evita que se repita.
-- Idempotente: no hace nada si la línea ya tiene salida ONLINE_SALE_PREPARE. Corre en una sola transacción.
-- Antes de aplicar en prod, revisar con: SELECT ... del bloque «candidatas».
BEGIN;

WITH origenes AS (SELECT id, CASE code WHEN 'BODEGA_PT' THEN 1 ELSE 2 END AS prioridad FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION')),
candidatas AS (
    SELECT i.id AS line_id, i.online_sale_id, i.product_id, i.color_id, COALESCE(i.quantity, 1) AS qty,
           s.shipment_number, s.sale_number, s.customer_name
    FROM online_sale_item i
    JOIN online_sale s ON s.id = i.online_sale_id
    WHERE i.id IN (3133, 3142)
      AND s.status IN ('ENVIADO', 'ENTREGADO')
      AND UPPER(TRIM(i.fulfillment_route)) = 'DISPATCH'
      AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex k
                      WHERE k.reference_type = 'ONLINE_SALE_PREPARE' AND k.reference_id = i.online_sale_id
                        AND k.reference_line_id = i.id)
),
con_stock AS (
    SELECT DISTINCT ON (c.line_id) c.*, pil.id AS pil_id, pil.location_id, pil.quantity AS antes
    FROM candidatas c
    JOIN origenes o ON TRUE
    JOIN product_inventory_location pil
      ON pil.product_id = c.product_id AND pil.location_id = o.id AND pil.color_id = c.color_id
    WHERE pil.quantity >= c.qty
    ORDER BY c.line_id, o.prioridad
),
bajar AS (
    UPDATE product_inventory_location pil
       SET quantity = pil.quantity - c.qty, updated_at = NOW()
      FROM con_stock c
     WHERE pil.id = c.pil_id
    RETURNING c.line_id
)
INSERT INTO product_inventory_kardex
    (created_at, description, location_id, movement_date, movement_type, product_id, quantity,
     quantity_before, quantity_after, reference_id, reference_number, reference_type, color_id, reference_line_id)
SELECT NOW(),
       'Corrección: despacho venta en línea ' || COALESCE(c.shipment_number, '#' || c.online_sale_id)
         || ' · cliente ' || COALESCE(c.customer_name, '')
         || ' · línea marcada «despachar desde inventario» que se envió sin descontar de Bodega PT o Devoluciones',
       c.location_id, NOW(), 'ONLINE_SALE_PREPARE', c.product_id, -c.qty,
       c.antes, c.antes - c.qty, c.online_sale_id, c.sale_number, 'ONLINE_SALE_PREPARE', c.color_id, c.line_id
FROM con_stock c
WHERE c.line_id IN (SELECT line_id FROM bajar);

-- Verificación
SELECT reference_id, product_id, color_id, quantity, quantity_before, quantity_after
FROM product_inventory_kardex
WHERE reference_type = 'ONLINE_SALE_PREPARE' AND reference_id IN (2540, 2544) AND description LIKE 'Corrección%';

COMMIT;
