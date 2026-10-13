-- ---------------------------------------------------------------------------
-- DIAGNOSTICO (solo lectura): ventas de Entrecueros donde una linea no descontó inventario.
-- Para cada linea de las ventas indicadas muestra la marca que quedo guardada en la linea (si hay),
-- cuantas unidades descontó el sistema por marca y cuanto hay hoy en cada stock del producto/color.
-- Cambiar la lista de ventas si hace falta.
-- ---------------------------------------------------------------------------
SELECT ks.sale_number,
       i.product_code AS producto,
       i.color_name AS color,
       NULLIF(substring(i.product_name from ' T\.([^ ]+)$'), '') AS talla,
       i.quantity AS cantidad_linea,
       COALESCE(i.hardware_condition, '(sin marca guardada)') AS marca_en_la_linea,
       (SELECT string_agg(s.hardware_condition || ' x' || m.quantity, ', ' ORDER BY m.id)
          FROM kiosco_movement m
          JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
         WHERE m.reference_id = ks.id AND m.movement_type = 'VENTA'
           AND s.product_id = i.product_id AND s.color_id IS NOT DISTINCT FROM i.color_id
           AND COALESCE(btrim(m.size_key), '') = COALESCE(substring(i.product_name from ' T\.([^ ]+)$'), '')
       ) AS descontado_por_el_sistema,
       (SELECT string_agg(o.hardware_condition || '=' || o.current_stock, ', ' ORDER BY o.hardware_condition)
          FROM kiosco_stock o
         WHERE o.location_id = ks.kiosk_location_id AND o.product_id = i.product_id
           AND o.color_id IS NOT DISTINCT FROM i.color_id
       ) AS stock_actual_por_marca
FROM kiosk_sale ks
JOIN kiosk_sale_item i ON i.kiosk_sale_id = ks.id
WHERE ks.sale_number IN (
        'POS-20260922-0001', 'POS-20260922-0029', 'POS-20261009-0026')
  AND ((i.product_code IN ('B-14', 'B-21') ) OR (i.product_code = 'T-92'))
ORDER BY ks.sale_number, i.product_code, i.color_name, i.id;
