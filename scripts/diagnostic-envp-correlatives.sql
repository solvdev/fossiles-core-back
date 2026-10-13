-- Diagnóstico (solo lectura) del correlativo ENVP-nnnnn.
-- El siguiente número es siempre MAX(en uso) + 1, mirando production_order.vendor_shipment_number
-- y product_shipment.shipment_number (solo formato exacto ENVP-nnnnn). Nunca reutiliza huecos.

-- 1. Números repetidos entre órdenes (debe devolver 0 filas).
SELECT vendor_shipment_number, count(*) AS ordenes, string_agg(code, ', ' ORDER BY code) AS codigos
FROM production_order
WHERE vendor_shipment_number ~ '^ENVP-\d+$'
GROUP BY vendor_shipment_number
HAVING count(*) > 1;

-- 2. Huecos: números entre 1 y el máximo que no usa ninguna orden ni ningún envío.
WITH usados AS (
    SELECT (regexp_match(vendor_shipment_number, '^ENVP-(\d+)$'))[1]::int AS n
    FROM production_order WHERE vendor_shipment_number ~ '^ENVP-\d+$'
    UNION
    SELECT (regexp_match(shipment_number, '^ENVP-(\d+)$'))[1]::int
    FROM product_shipment WHERE shipment_number ~ '^ENVP-\d+$'
)
SELECT 'ENVP-' || lpad(g::text, 5, '0') AS envp_sin_usar
FROM generate_series(1, (SELECT max(n) FROM usados)) g
WHERE g NOT IN (SELECT n FROM usados)
ORDER BY g;

-- 3. Números que quedaron en un envío ANULADO y ya no pertenecen a la orden (la orden rotó a otro).
SELECT s.shipment_number, s.status, po.code AS orden, po.vendor_shipment_number AS envp_actual_de_la_orden
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
WHERE s.shipment_number ~ '^ENVP-\d+$'
  AND po.vendor_shipment_number IS DISTINCT FROM s.shipment_number
ORDER BY s.shipment_number;

-- 4. Órdenes con ENVP asignado desde que se crearon pero sin ningún envío (el número se consume al crear la OP).
SELECT po.vendor_shipment_number, po.code, po.status, po.created_at
FROM production_order po
WHERE po.vendor_shipment_number ~ '^ENVP-\d+$'
  AND NOT EXISTS (SELECT 1 FROM product_shipment s WHERE s.production_order_id = po.id)
ORDER BY po.vendor_shipment_number;
