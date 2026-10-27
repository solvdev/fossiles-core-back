-- =============================================================================
-- AUDITORÍA DE INVARIANTES DEL INVENTARIO DE PRODUCTO TERMINADO — PostgreSQL (solo lectura)
-- =============================================================================
-- Cada consulta debe devolver 0 filas. Si devuelve algo, esa fila es un movimiento mal registrado.
-- Complementa audit-cuadre-inventario-pt.sql (cuadre por documento) con reglas que no deben romperse:
--   1. El stock de cada fila es la suma de su kardex.
--   2. La cadena quantity_before/after no se rompe.
--   3. Ninguna fila de stock está en negativo.
--   4. Toda pieza recibida tiene su entrada en la ubicación que le corresponde
--      (Bodega PT; la reserva para OPV/OPC de Luis Felipe) o ya salió de ahí.
--   5. La reserva de Luis Felipe = piezas recibidas cuya recepción sigue abierta.
--   6. Las salidas de OP a pedido (OPL, OPCK, OPI, OPC no LF) no salen de Devoluciones
--      mientras Bodega PT tenía la pieza (desde la fecha de corte).
--   7. Toda pieza de venta en línea marcada como enviada tiene su salida en el kardex.
-- Parámetro: fecha desde la cual aplican las reglas 4 y 6 (despliegue del cambio de origen).
-- =============================================================================

WITH param AS (SELECT DATE '2026-10-27' AS desde)
SELECT 'fecha de corte' AS dato, desde FROM param;

-- 1. Stock = suma de kardex (PT, Devoluciones, no aptas, tránsito, reserva)
SELECT '1 stock<>kardex' AS regla, l.code AS ubicacion, p.code AS producto, pil.color_id,
       pil.quantity AS stock, COALESCE(k.s, 0) AS kardex
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id AND l.code IN ('BODEGA_PT', 'DEVOLUCION', 'DEV_NO_APTA', 'EN_TRANSITO', 'RESERVA_LF')
JOIN product p ON p.id = pil.product_id
LEFT JOIN (SELECT product_id, location_id, COALESCE(color_id, 0) AS c, SUM(quantity) AS s
           FROM product_inventory_kardex GROUP BY 1, 2, 3) k
       ON k.product_id = pil.product_id AND k.location_id = pil.location_id AND k.c = COALESCE(pil.color_id, 0)
WHERE ABS(pil.quantity - COALESCE(k.s, 0)) > 0.0001;

-- 2. Cadena quantity_before/after
WITH k AS (
    SELECT id, product_id, location_id, color_id, movement_type, quantity_before,
           LAG(quantity_after) OVER (PARTITION BY product_id, location_id, COALESCE(color_id, 0) ORDER BY id) AS prev_after
    FROM product_inventory_kardex
    WHERE location_id IN (SELECT id FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION', 'DEV_NO_APTA', 'EN_TRANSITO', 'RESERVA_LF'))
)
SELECT '2 cadena rota' AS regla, k.id AS kardex_id, k.movement_type, k.product_id, k.location_id,
       k.quantity_before, k.prev_after
FROM k WHERE prev_after IS NOT NULL AND quantity_before <> prev_after;

-- 3. Stock negativo
SELECT '3 stock negativo' AS regla, l.code AS ubicacion, p.code AS producto, pil.color_id, pil.quantity
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
WHERE pil.quantity < 0 AND l.code IN ('BODEGA_PT', 'DEVOLUCION', 'DEV_NO_APTA', 'EN_TRANSITO', 'RESERVA_LF');

-- 4. Pieza recibida sin entrada en la ubicación correcta (ni salida posterior)
WITH param AS (SELECT DATE '2026-10-15' AS desde),
unidades AS (
    SELECT u.id AS unit_id, po.code AS orden, i.product_id, i.color_id,
           (UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
             AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')) AS es_reserva
    FROM production_order_warehouse_unit u
    JOIN production_order po ON po.id = u.production_order_id
    JOIN production_order_item i ON i.id = u.production_order_item_id
    WHERE u.receipt_status = 'RECEIVED' AND u.received_at >= (SELECT desde FROM param)
)
SELECT '4 recibida sin entrada' AS regla, un.orden, un.unit_id, un.es_reserva
FROM unidades un
WHERE NOT EXISTS (
    SELECT 1 FROM product_inventory_kardex k
    WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = un.unit_id
      AND k.location_id = (SELECT id FROM locations WHERE code = CASE WHEN un.es_reserva THEN 'RESERVA_LF' ELSE 'BODEGA_PT' END)
      -- OPV/OPC de LF recibidas antes del cambio entraron a PT y llegan a la reserva por traslado
      AND (k.movement_type = 'PRODUCTION_ENTRY' OR (un.es_reserva AND k.movement_type = 'RESERVE_TRANSFER_IN')));

-- 5. Reserva LF = piezas recibidas cuya recepción sigue abierta (por producto/color)
WITH esperadas AS (
    SELECT i.product_id, i.color_id, COUNT(*)::numeric AS piezas
    FROM production_order po
    JOIN production_order_warehouse_unit u ON u.production_order_id = po.id AND u.receipt_status = 'RECEIVED'
    JOIN production_order_item i ON i.id = u.production_order_item_id
    WHERE UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%' AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
      AND po.warehouse_receipt_closed_at IS NULL
      AND EXISTS (SELECT 1 FROM product_inventory_kardex k
                  WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
                    AND k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF'))
    GROUP BY 1, 2
),
reserva AS (
    SELECT pil.product_id, pil.color_id, pil.quantity
    FROM product_inventory_location pil
    WHERE pil.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
)
SELECT '5 reserva distinta de piezas abiertas' AS regla, COALESCE(e.product_id, r.product_id) AS product_id,
       COALESCE(e.color_id, r.color_id) AS color_id, COALESCE(e.piezas, 0) AS piezas_abiertas, COALESCE(r.quantity, 0) AS reserva
FROM esperadas e
FULL JOIN reserva r ON r.product_id = e.product_id AND COALESCE(r.color_id, 0) = COALESCE(e.color_id, 0)
WHERE COALESCE(e.piezas, 0) <> COALESCE(r.quantity, 0);

-- 6. Salida de OP a pedido desde Devoluciones habiendo stock en PT (desde el corte)
WITH param AS (SELECT DATE '2026-10-27' AS desde)
SELECT '6 salida de OP a pedido desde Devoluciones' AS regla, k.id AS kardex_id, k.movement_type,
       p.code AS producto, k.color_id, -k.quantity AS unidades, k.reference_number, k.movement_date
FROM product_inventory_kardex k
JOIN product p ON p.id = k.product_id
LEFT JOIN product_shipment s ON k.reference_type = 'SHIPMENT' AND s.id = k.reference_id
LEFT JOIN production_order po ON po.id = s.production_order_id
WHERE k.location_id = (SELECT id FROM locations WHERE code = 'DEVOLUCION') AND k.quantity < 0
  AND k.movement_date >= (SELECT desde FROM param)
  AND (k.movement_type = 'ONLINE_SALE_DISPATCH'
       OR (po.order_type IN ('CLIENTE_KIOSKO', 'INTERNA') OR po.order_type LIKE 'CINCHOS%')
          AND UPPER(COALESCE(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%');

-- 7. Pieza de venta en línea marcada como enviada sin salida en el kardex
SELECT '7 enviada sin salida' AS regla, po.code AS orden, u.id AS unit_id, u.shipment_ref_id AS venta_id
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE u.shipment_ref_type = 'ONLINE_SALE' AND u.shipped_at >= DATE '2026-10-15'
  AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex k
                  WHERE k.reference_type = 'ONLINE_SALE_DISPATCH' AND k.reference_id = u.shipment_ref_id
                    AND k.reference_line_id = u.id AND k.quantity < 0)
  AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex k
                  WHERE k.reference_type = 'ONLINE_SALE_PREPARE' AND k.reference_id = u.shipment_ref_id
                    AND k.quantity < 0);
