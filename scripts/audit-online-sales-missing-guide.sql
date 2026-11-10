-- SOLO LECTURA. Ventas en línea despachadas por Forza o Guatex sin número de guía.
-- Es la lista de trabajo para recuperar las guías históricas desde el portal del transportista
-- (el portal muestra el ENVL y los datos del cliente). No modifica nada.

-- 1) Resumen por mes y transportista
SELECT to_char(sale_date, 'YYYY-MM')                                   AS mes,
       shipping_carrier                                                 AS transportista,
       COUNT(*)                                                         AS enviadas,
       COUNT(*) FILTER (WHERE NULLIF(btrim(guide_number), '') IS NULL)  AS sin_guia,
       COUNT(*) FILTER (WHERE NULLIF(btrim(guide_number), '') IS NOT NULL) AS con_guia
FROM online_sale
WHERE status = 'ENVIADO'
  AND shipping_carrier IN ('FORZA_DELIVERY', 'GUATEX')
GROUP BY 1, 2
ORDER BY 1, 2;

-- 2) Detalle de lo que falta (ENVL + cliente: es lo que se busca en el portal)
SELECT id, sale_date, shipment_number AS envl, shipping_carrier AS transportista,
       customer_name AS cliente, phone AS telefono, total_amount AS total, payment_method AS forma_pago
FROM online_sale
WHERE status = 'ENVIADO'
  AND shipping_carrier IN ('FORZA_DELIVERY', 'GUATEX')
  AND NULLIF(btrim(guide_number), '') IS NULL
ORDER BY sale_date, id;

-- 3) Ventas despachadas sin ENVL (no se podrán buscar por número de envío)
SELECT id, sale_date, shipping_carrier, customer_name, total_amount
FROM online_sale
WHERE status = 'ENVIADO'
  AND NULLIF(btrim(shipment_number), '') IS NULL
ORDER BY sale_date, id;
