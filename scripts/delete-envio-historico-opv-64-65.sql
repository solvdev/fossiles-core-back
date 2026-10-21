-- OPV-64 (ENVP-00150) y OPV-65 (ENVP-00151) no salen en Preparar envíos > Órdenes sin envío.
-- Causa: backfill-historical-shipments-for-receivables.sql (08-oct) les creó un "ENVIO HISTORICO" en CONFIRMED,
-- sin líneas, porque ya tenían cargo en CxC. Pero estas órdenes NO habían salido: siguen PENDING y su producto
-- entró a bodega después (recepción cerrada 19-oct / 21-oct; 31 y 39 unidades RECEIVED, ninguna despachada).
-- Con ese envío "activo" la orden ya no cuenta como pendiente, y si se pasara a SENT marcaría todo como
-- despachado SIN sacar el inventario de Bodega PT.
--
-- Se borra el envío histórico vacío (no "Anular": anular rota el ENVP a otro número). Así la orden queda sin
-- envío, vuelve a "Órdenes sin envío" y el envío real se genera con su mismo ENVP-00150 / ENVP-00151.
-- El cargo de CxC es por orden completa (product_shipment_id NULL): no se toca.

-- 1) Vista previa: 2 filas, CONFIRMED, sent_at vacío, lineas = 0, cargos = 0, isr = 0.
SELECT s.id, s.shipment_number, s.status, s.sent_at, po.code, po.status AS order_status,
       left(s.notes, 30) AS notes,
       (SELECT count(*) FROM product_shipment_detail d WHERE d.shipment_id = s.id) AS lineas,
       (SELECT count(*) FROM customer_account_entry e WHERE e.product_shipment_id = s.id) AS cargos,
       (SELECT count(*) FROM internal_shipment_request r WHERE r.product_shipment_id = s.id) AS isr,
       (SELECT count(*) FROM production_order_warehouse_unit u
         WHERE u.production_order_id = po.id AND u.shipped_at IS NOT NULL) AS unidades_despachadas
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
WHERE s.shipment_number IN ('ENVP-00150', 'ENVP-00151');

-- 2) Borrado con guardas: solo el histórico vacío, nunca enviado, de esas dos órdenes.
BEGIN;

DELETE FROM product_shipment s
USING production_order po
WHERE po.id = s.production_order_id
  AND po.code IN ('OPV-64', 'OPV-65')
  AND s.shipment_number IN ('ENVP-00150', 'ENVP-00151')
  AND s.notes LIKE 'ENVIO HISTORICO%'
  AND s.status = 'CONFIRMED'
  AND s.sent_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM product_shipment_detail d WHERE d.shipment_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM customer_account_entry e WHERE e.product_shipment_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM internal_shipment_request r WHERE r.product_shipment_id = s.id);
-- Debe decir DELETE 2. Si dice otra cosa, ROLLBACK y revisar.

COMMIT;
