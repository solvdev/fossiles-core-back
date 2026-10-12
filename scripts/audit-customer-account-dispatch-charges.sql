-- AUDITORIA (solo lectura) del cargo por despacho. No cambia nada.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f audit-customer-account-dispatch-charges.sql
SELECT current_database() AS base;

-- 1) Envios despachados (SENT/DELIVERED) de ordenes con cliente que NO tienen cargo activo y cuya orden tampoco
--    tiene un cargo por orden completa. Son los "despachados sin cargo": el sistema los muestra con estado NONE.
--    Los envios anteriores al cambio no se cargan solos: revisar uno por uno y generar el cargo del envio si aplica.
SELECT s.id AS shipment_id, s.shipment_number, s.status, s.sent_at, po.id AS order_id, po.code AS order_code,
       po.customer_id
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
WHERE UPPER(s.status) IN ('SENT', 'DELIVERED')
  AND po.customer_id IS NOT NULL
  AND UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
  AND UPPER(COALESCE(po.order_type, '')) NOT IN ('INTERNA', 'CLIENTE_KIOSKO')
  AND NOT EXISTS (SELECT 1 FROM customer_account_entry e
                  WHERE e.entry_type = 'CHARGE' AND e.status <> 'VOID' AND e.product_shipment_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM customer_account_entry e
                  WHERE e.entry_type = 'CHARGE' AND e.status <> 'VOID'
                    AND e.production_order_id = po.id AND e.product_shipment_id IS NULL)
ORDER BY s.sent_at NULLS LAST, s.id;

-- 2) Cargos por orden completa (modelo anterior) cuya orden tiene envios sin despachar o ninguno.
--    En pantalla y reportes ya se muestran recortados a lo despachado; el libro conserva el monto original.
SELECT e.id AS charge_id, e.customer_id, e.production_order_id, po.code AS order_code, e.amount,
       count(s.id) AS envios_total,
       count(s.id) FILTER (WHERE UPPER(s.status) IN ('SENT', 'DELIVERED')) AS envios_despachados
FROM customer_account_entry e
JOIN production_order po ON po.id = e.production_order_id
LEFT JOIN product_shipment s ON s.production_order_id = po.id
WHERE e.entry_type = 'CHARGE' AND e.status <> 'VOID' AND e.product_shipment_id IS NULL
GROUP BY e.id, e.customer_id, e.production_order_id, po.code, e.amount
HAVING count(s.id) FILTER (WHERE UPPER(s.status) IN ('SENT', 'DELIVERED')) < count(s.id)
    OR count(s.id) = 0
ORDER BY e.customer_id, e.id;

-- 3) Cargos o ajustes activos ligados a un envio que NO esta despachado (no deberian existir; la cartera los oculta).
SELECT e.id AS entry_id, e.entry_type, e.amount, e.product_shipment_id, s.shipment_number, s.status
FROM customer_account_entry e
JOIN product_shipment s ON s.id = e.product_shipment_id
WHERE e.entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
  AND e.status <> 'VOID'
  AND UPPER(s.status) NOT IN ('SENT', 'DELIVERED')
ORDER BY e.id;
