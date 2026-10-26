-- ---------------------------------------------------------------------------
-- AUDITORIA (solo lectura, no modifica nada): ventas POS vs movimientos de inventario (kardex / conteo).
-- Kiosco y periodo: cambiar los dos valores del bloque "params" de cada consulta.
--
-- 1) Lineas de venta NO anuladas sin su movimiento VENTA (o con otra cantidad).
-- 2) Ventas ANULADAS donde lo anulado no es igual a lo vendido.
-- 3) Linea de venta con una marca y movimiento VENTA en el stock de otra marca.
-- 4) Movimientos VENTA / ANULACION que no corresponden a ninguna venta del kiosco (huerfanos).
-- 5) Stock "NUEVO" con existencias aunque el producto/color ya tiene stock por marca o niño/dama.
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- 1) Ventas vigentes cuyo movimiento VENTA no cuadra con la linea vendida
--    (talla = texto despues de " T." en el nombre de la linea)
-- ===========================================================================
WITH p AS (
    SELECT l.id AS location_id, TIMESTAMP '2026-09-10 00:00' AS d_from, TIMESTAMP '2026-10-12 00:00' AS d_to
    FROM locations l WHERE l.name ILIKE 'ENTRECUEROS PUEBLITO'
),
lines AS (
    SELECT ks.id AS sale_id, ks.sale_number, ks.sold_at, ks.promotion_name, ks.shipping_sheet_number,
           i.product_id, i.product_code, i.color_id, i.color_name,
           NULLIF(substring(i.product_name from ' T\.([^ ]+)$'), '') AS talla,
           SUM(i.quantity)::int AS qty_linea
    FROM kiosk_sale ks
    JOIN p ON p.location_id = ks.kiosk_location_id
    JOIN kiosk_sale_item i ON i.kiosk_sale_id = ks.id
    WHERE ks.sold_at >= p.d_from AND ks.sold_at < p.d_to
      AND ks.status <> 'VOID'
      AND COALESCE(ks.test_sale, FALSE) = FALSE
    GROUP BY ks.id, ks.sale_number, ks.sold_at, ks.promotion_name, ks.shipping_sheet_number,
             i.product_id, i.product_code, i.color_id, i.color_name,
             NULLIF(substring(i.product_name from ' T\.([^ ]+)$'), '')
),
venta AS (
    SELECT m.reference_id AS sale_id, s.product_id, s.color_id,
           NULLIF(btrim(m.size_key), '') AS talla,
           SUM(m.quantity)::int AS qty_venta
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    JOIN p ON p.location_id = s.location_id
    WHERE m.movement_type = 'VENTA'
    GROUP BY m.reference_id, s.product_id, s.color_id, NULLIF(btrim(m.size_key), '')
)
SELECT l.sale_number, l.sold_at, l.shipping_sheet_number AS hoja_envio, l.promotion_name,
       l.product_code, l.color_name, l.talla, l.qty_linea,
       COALESCE(v.qty_venta, 0) AS qty_movimiento_venta,
       l.qty_linea - COALESCE(v.qty_venta, 0) AS falta_en_kardex
FROM lines l
LEFT JOIN venta v ON v.sale_id = l.sale_id AND v.product_id = l.product_id
                 AND v.color_id IS NOT DISTINCT FROM l.color_id
                 AND v.talla IS NOT DISTINCT FROM l.talla
WHERE l.qty_linea <> COALESCE(v.qty_venta, 0)
ORDER BY l.sold_at, l.sale_number, l.product_code;

-- ===========================================================================
-- 2) Ventas anuladas: lo vendido vs lo anulado por producto/color/talla
-- ===========================================================================
WITH p AS (
    SELECT l.id AS location_id, TIMESTAMP '2026-09-10 00:00' AS d_from, TIMESTAMP '2026-10-12 00:00' AS d_to
    FROM locations l WHERE l.name ILIKE 'ENTRECUEROS PUEBLITO'
),
mov AS (
    SELECT m.reference_id AS sale_id, s.product_id, s.color_id,
           NULLIF(btrim(m.size_key), '') AS talla,
           SUM(CASE WHEN m.movement_type = 'VENTA' THEN m.quantity ELSE 0 END)::int AS vendido,
           SUM(CASE WHEN m.movement_type = 'ANULACION' THEN m.quantity ELSE 0 END)::int AS anulado
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    JOIN p ON p.location_id = s.location_id
    WHERE m.movement_type IN ('VENTA', 'ANULACION')
    GROUP BY m.reference_id, s.product_id, s.color_id, NULLIF(btrim(m.size_key), '')
)
SELECT ks.sale_number, ks.sold_at, pr.code AS producto, c.name AS color, mv.talla, mv.vendido, mv.anulado
FROM mov mv
JOIN kiosk_sale ks ON ks.id = mv.sale_id
JOIN p ON p.location_id = ks.kiosk_location_id
JOIN product pr ON pr.id = mv.product_id
LEFT JOIN colors c ON c.id = mv.color_id
WHERE ks.status = 'VOID'
  AND ks.sold_at >= p.d_from AND ks.sold_at < p.d_to
  AND mv.vendido <> mv.anulado
ORDER BY ks.sold_at, ks.sale_number, pr.code;

-- ===========================================================================
-- 3) La linea de venta dice una marca pero el movimiento VENTA salio del stock de otra
-- ===========================================================================
WITH p AS (
    SELECT l.id AS location_id, TIMESTAMP '2026-09-10 00:00' AS d_from, TIMESTAMP '2026-10-12 00:00' AS d_to
    FROM locations l WHERE l.name ILIKE 'ENTRECUEROS PUEBLITO'
)
SELECT ks.sale_number, ks.status, ks.sold_at, i.product_code, i.color_name, i.product_name,
       i.hardware_condition AS marca_en_la_linea,
       s.hardware_condition AS marca_del_stock_descontado,
       m.quantity, m.size_key
FROM kiosk_sale ks
JOIN p ON p.location_id = ks.kiosk_location_id
JOIN kiosk_sale_item i ON i.kiosk_sale_id = ks.id
JOIN kiosco_movement m ON m.reference_id = ks.id AND m.movement_type = 'VENTA'
JOIN kiosco_stock s ON s.id = m.kiosco_stock_id AND s.location_id = ks.kiosk_location_id
                   AND s.product_id = i.product_id AND s.color_id IS NOT DISTINCT FROM i.color_id
WHERE ks.sold_at >= p.d_from AND ks.sold_at < p.d_to
  AND i.hardware_condition IS NOT NULL
  AND i.hardware_condition <> s.hardware_condition
ORDER BY ks.sold_at, ks.sale_number;

-- ===========================================================================
-- 4) Movimientos VENTA / ANULACION del periodo cuyo reference_id no es una venta de este kiosco
--    (pueden ser de facturas/otros orígenes; revisar caso por caso)
-- ===========================================================================
WITH p AS (
    SELECT l.id AS location_id, TIMESTAMP '2026-09-10 00:00' AS d_from, TIMESTAMP '2026-10-12 00:00' AS d_to
    FROM locations l WHERE l.name ILIKE 'ENTRECUEROS PUEBLITO'
)
SELECT m.id AS movimiento, m.movement_type, m.created_at, m.reference_id,
       pr.code AS producto, c.name AS color, s.hardware_condition, m.size_key, m.quantity, m.reason
FROM kiosco_movement m
JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
JOIN p ON p.location_id = s.location_id
JOIN product pr ON pr.id = s.product_id
LEFT JOIN colors c ON c.id = s.color_id
WHERE m.movement_type IN ('VENTA', 'ANULACION')
  AND m.created_at >= p.d_from AND m.created_at < p.d_to
  AND NOT EXISTS (SELECT 1 FROM kiosk_sale ks WHERE ks.id = m.reference_id AND ks.kiosk_location_id = s.location_id)
ORDER BY m.created_at;

-- ===========================================================================
-- 5) Stock NUEVO con existencias aunque el producto/color ya tiene stock por marca o niño/dama
--    (en Entrecueros esas unidades deberian estar en la marca / niño-dama)
-- ===========================================================================
SELECT pr.code AS producto, c.name AS color, s.id AS stock_id, s.current_stock, s.sizes_data,
       (SELECT string_agg(o.hardware_condition || '=' || o.current_stock, ', ' ORDER BY o.hardware_condition)
        FROM kiosco_stock o
        WHERE o.location_id = s.location_id AND o.product_id = s.product_id
          AND o.color_id IS NOT DISTINCT FROM s.color_id
          AND o.hardware_condition NOT IN ('NUEVO', 'VIEJO')) AS otros_stocks
FROM kiosco_stock s
JOIN locations l ON l.id = s.location_id AND l.name ILIKE 'ENTRECUEROS PUEBLITO'
JOIN product pr ON pr.id = s.product_id
LEFT JOIN colors c ON c.id = s.color_id
WHERE s.hardware_condition IN ('NUEVO', 'VIEJO')
  AND COALESCE(s.current_stock, 0) > 0
  AND EXISTS (
      SELECT 1 FROM kiosco_stock o
      WHERE o.location_id = s.location_id AND o.product_id = s.product_id
        AND o.color_id IS NOT DISTINCT FROM s.color_id
        AND o.hardware_condition NOT IN ('NUEVO', 'VIEJO'))
ORDER BY pr.code, c.name;
