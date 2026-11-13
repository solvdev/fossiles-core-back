-- Auditoría (SOLO LECTURA): ventas POS con promoción por pieza cuya factura prorrateó el descuento.
-- Antes del fix, FelFactXmlBuilder repartía el descuento total entre las líneas por precio y no usaba
-- kiosk_sale_item.discount_amount. El total de la factura y lo cobrado en el POS sí cuadran; lo que
-- sale distinto es el neto de cada línea en la factura (ej. Q395 al 15 % = 335.75 salía 317.38).
-- Afectada = venta con descuento por línea distinto de un % uniforme (≥2 líneas con % diferente).

-- 1) Resumen por día y kiosco
WITH item AS (
  SELECT s.id AS sale_id, s.sale_number, s.kiosk_location_id, s.sold_at, s.status, s.fel_status, s.fel_uuid,
         s.subtotal, s.discount_amount AS sale_discount, s.total_amount,
         i.id AS item_id, i.product_name, i.quantity,
         (i.unit_price - COALESCE(i.extra_amount, 0)) * i.quantity AS base_total,
         COALESCE(i.discount_amount, 0) AS line_discount
  FROM kiosk_sale s
  JOIN kiosk_sale_item i ON i.kiosk_sale_id = s.id
  WHERE s.promotion_rule_snapshot IS NOT NULL
    AND COALESCE(s.discount_amount, 0) > 0
    AND i.discount_amount IS NOT NULL
), pct AS (
  SELECT *, ROUND(100 * line_discount / NULLIF(base_total, 0), 1) AS pct_line
  FROM item
), affected AS (
  SELECT sale_id
  FROM pct
  WHERE line_discount > 0
  GROUP BY sale_id
  HAVING COUNT(DISTINCT pct_line) > 1
)
SELECT date(p.sold_at) AS dia, p.kiosk_location_id,
       COUNT(DISTINCT p.sale_id) AS ventas_afectadas,
       COUNT(DISTINCT p.sale_id) FILTER (WHERE p.fel_uuid IS NOT NULL) AS con_factura_certificada
FROM pct p JOIN affected a ON a.sale_id = p.sale_id
GROUP BY 1, 2 ORDER BY 1, 2;

-- 2) Detalle por línea: neto correcto vs neto que quedó en la factura (prorrateo aproximado)
WITH item AS (
  SELECT s.id AS sale_id, s.sale_number, s.kiosk_location_id, s.sold_at, s.fel_status, s.fel_serie, s.fel_numero,
         s.subtotal, s.discount_amount AS sale_discount,
         i.product_name, i.quantity, i.line_total,
         COALESCE(i.discount_amount, 0) AS line_discount,
         (i.unit_price - COALESCE(i.extra_amount, 0)) * i.quantity AS base_total
  FROM kiosk_sale s
  JOIN kiosk_sale_item i ON i.kiosk_sale_id = s.id
  WHERE s.promotion_rule_snapshot IS NOT NULL
    AND COALESCE(s.discount_amount, 0) > 0
    AND i.discount_amount IS NOT NULL
), calc AS (
  SELECT *,
         ROUND(100 * line_discount / NULLIF(base_total, 0), 1) AS pct_line,
         SUM(base_total) OVER (PARTITION BY sale_id) AS elegible_total
  FROM item
)
SELECT sale_number, kiosk_location_id, sold_at, fel_serie, fel_numero, product_name, quantity,
       line_total AS precio_lista, pct_line AS pct_correcto,
       line_total - line_discount AS neto_correcto,
       ROUND(line_total - sale_discount * base_total / NULLIF(elegible_total, 0), 2) AS neto_en_factura_aprox
FROM calc
WHERE sale_id IN (SELECT sale_id FROM calc WHERE line_discount > 0 GROUP BY sale_id HAVING COUNT(DISTINCT pct_line) > 1)
ORDER BY sold_at, sale_number, product_name;
