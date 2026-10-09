-- Auditoría (solo lectura) de cargos en cuentas por cobrar: quién y cuándo los creó,
-- de qué documento salen y cuánto se esperaba cobrar. Cambiar el cliente en :customer_name.

SELECT e.id,
       e.status,
       e.entry_date                         AS fecha_cargo,
       e.created_at                         AS creado_el,
       btrim(coalesce(u.first_name, '') || ' ' || coalesce(u.last_name, '')) AS creado_por,
       e.vendor_shipment_number             AS envp,
       po.code                              AS orden,
       e.partial_release_id,
       pr.sequence_num                      AS parcial_no,
       e.product_shipment_id,
       s.shipment_number                    AS envio,
       s.status                             AS estado_envio,
       e.amount                             AS monto_cargo,
       e.description,
       e.voided_at,
       e.void_reason
FROM customer_account_entry e
JOIN customer c ON c.id = e.customer_id
LEFT JOIN users u ON u.id = e.created_by
LEFT JOIN production_order po ON po.id = e.production_order_id
LEFT JOIN production_order_partial_release pr ON pr.id = e.partial_release_id
LEFT JOIN product_shipment s ON s.id = e.product_shipment_id
WHERE e.entry_type = 'CHARGE'
  AND c.name ILIKE '%VILMA RUANO%'
ORDER BY e.created_at DESC;

-- Cargos creados hoy cuya fecha de cargo no es hoy, o con la misma orden/ENVP repetida (posibles duplicados).
SELECT e.vendor_shipment_number, po.code AS orden, count(*) AS cargos_activos, sum(e.amount) AS total
FROM customer_account_entry e
LEFT JOIN production_order po ON po.id = e.production_order_id
WHERE e.entry_type = 'CHARGE' AND e.status = 'ACTIVE'
GROUP BY e.vendor_shipment_number, po.code
HAVING count(*) > 1
ORDER BY e.vendor_shipment_number;

-- Cargos activos de la misma orden con alcance cruzado (orden/parcial/env�o): el segundo es el duplicado.
SELECT a.production_order_id, po.code AS orden,
       a.id AS cargo_a, a.vendor_shipment_number AS envp_a, a.amount AS monto_a, a.created_at AS creado_a,
       b.id AS cargo_b, b.vendor_shipment_number AS envp_b, b.amount AS monto_b, b.created_at AS creado_b
FROM customer_account_entry a
JOIN customer_account_entry b
  ON b.production_order_id = a.production_order_id AND b.id > a.id
JOIN production_order po ON po.id = a.production_order_id
WHERE a.entry_type = 'CHARGE' AND b.entry_type = 'CHARGE'
  AND a.status = 'ACTIVE' AND b.status = 'ACTIVE'
  AND (
        (a.product_shipment_id IS NOT NULL AND a.product_shipment_id = b.product_shipment_id)
     OR (a.partial_release_id IS NULL AND a.product_shipment_id IS NULL)
     OR (b.partial_release_id IS NULL AND b.product_shipment_id IS NULL)
     OR (a.product_shipment_id IS NULL OR b.product_shipment_id IS NULL)
        AND a.partial_release_id IS NOT DISTINCT FROM b.partial_release_id
  )
ORDER BY po.code, a.id;
