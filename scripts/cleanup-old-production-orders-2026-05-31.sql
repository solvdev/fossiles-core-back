-- =====================================================================
-- Cierre masivo de órdenes de producción antiguas (fecha Guatemala)
-- =====================================================================
-- Objetivo: para toda orden de producción cuya fecha de referencia sea
-- <= 2026-05-31, forzar cada tabla del ciclo de vida a su estado FINAL
-- (recibido / completado / enviado / entregado / aprobado), sin borrar
-- ningún registro.
--
-- Fecha de referencia de la orden = COALESCE(start_date, created_at::date)
-- (mismo criterio que usa el frontend para mostrar la fecha de la OP).
--
-- Se EXCLUYEN del cierre (quedan intactas, ya están en un estado final):
--   * production_order.status IN ('DRAFT', 'CANCELLED')
--   * Cualquier fila hija ya cancelada/anulada/rechazada/devuelta
--     (CANCELLED, CANCELADO, ANULADA, DEVOLUCION, RECHAZADA)
--
-- Ajusta la fecha '2026-05-31' en el WHERE de cada UPDATE si necesitas
-- otro corte.
--
-- IMPORTANTE: este script NO usa BEGIN/COMMIT. Cada UPDATE se confirma
-- solo (autocommit), así que no depende de un paso manual de "confirmar"
-- al final. Es seguro volver a correrlo las veces que hagan falta: todas
-- las condiciones son idempotentes (solo tocan filas que aún no están en
-- su estado final).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) production_order_warehouse_unit -> RECEIVED
--    (piezas individuales pendientes de recepción en bodega PT)
-- ---------------------------------------------------------------------
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.receipt_status = 'PENDING'
  AND u.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 2) production_order_item -> alinear cantidad recibida con lo pedido
--    Ojo: para ítems con desglose por talla, lo "pedido" es la suma de
--    sizes_data, NO la columna quantity (ahí suele venir 0/NULL).
-- ---------------------------------------------------------------------
UPDATE production_order_item i
SET warehouse_received_qty = COALESCE(
      (SELECT SUM((kv.value)::numeric)::int
       FROM jsonb_each_text(i.sizes_data::jsonb) AS kv(key, value)),
      i.quantity,
      0
    )
WHERE i.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 3) task -> COMPLETED
--    (tareas de producción pendientes/en progreso/esperando bodega)
-- ---------------------------------------------------------------------
UPDATE task t
SET status = 'COMPLETED',
    started_at = COALESCE(t.started_at, NOW()),
    completed_at = COALESCE(t.completed_at, NOW())
WHERE t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE')
  AND t.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 4) production_order -> cierre de recepción de bodega + status COMPLETED
-- ---------------------------------------------------------------------
UPDATE production_order po
SET warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW())
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED');

UPDATE production_order po
SET status = 'COMPLETED'
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status IN ('PENDING', 'IN_PROGRESS', 'IN_QA');

-- ---------------------------------------------------------------------
-- 5) online_sale -> ENVIADO (ventas en línea ligadas a la OP)
-- ---------------------------------------------------------------------
UPDATE online_sale os
SET status = 'ENVIADO'
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'CANCELADO', 'DEVOLUCION', 'ANULADA')
  AND (
    os.production_order_id IN (
      SELECT id FROM production_order
      WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
        AND status NOT IN ('DRAFT', 'CANCELLED')
    )
    OR os.id IN (
      SELECT poi.online_sale_id
      FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL
        AND poi.production_order_id IN (
          SELECT id FROM production_order
          WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
            AND status NOT IN ('DRAFT', 'CANCELLED')
        )
    )
  );

-- ---------------------------------------------------------------------
-- 6) product_shipment -> DELIVERED (envíos directos, por distribución
--    o por liberación parcial, ligados a la OP)
-- ---------------------------------------------------------------------
UPDATE product_shipment ps
SET status = 'DELIVERED',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = COALESCE(ps.received_at, NOW())
WHERE ps.status NOT IN ('DELIVERED', 'CANCELLED')
  AND (
    ps.production_order_id IN (
      SELECT id FROM production_order
      WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
        AND status NOT IN ('DRAFT', 'CANCELLED')
    )
    OR ps.distribution_id IN (
      SELECT distribution_id FROM production_order
      WHERE distribution_id IS NOT NULL
        AND COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
        AND status NOT IN ('DRAFT', 'CANCELLED')
    )
    OR ps.partial_release_id IN (
      SELECT r.id FROM production_order_partial_release r
      WHERE r.production_order_id IN (
        SELECT id FROM production_order
        WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
          AND status NOT IN ('DRAFT', 'CANCELLED')
      )
    )
  );

-- ---------------------------------------------------------------------
-- 7) product_distribution -> COMPLETED (distribución a kioskos)
-- ---------------------------------------------------------------------
UPDATE product_distribution pd
SET status = 'COMPLETED'
WHERE pd.status <> 'COMPLETED'
  AND pd.id IN (
    SELECT distribution_id FROM production_order
    WHERE distribution_id IS NOT NULL
      AND COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 8) production_order_partial_release -> SHIPPED (liberaciones parciales LF)
-- ---------------------------------------------------------------------
UPDATE production_order_partial_release r
SET status = 'SHIPPED'
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND r.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 9) internal_shipment_request -> APROBADA (solicitudes ENVI -> OPI)
-- ---------------------------------------------------------------------
UPDATE internal_shipment_request isr
SET status = 'APROBADA',
    reviewed_at = COALESCE(isr.reviewed_at, NOW())
WHERE isr.status = 'PENDIENTE'
  AND isr.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- 10) production_cincho_day_status -> COMPLETED + entregado (mesa cinchos)
-- ---------------------------------------------------------------------
UPDATE production_cincho_day_status c
SET work_status = 'COMPLETED',
    delivered = TRUE,
    delivered_at = COALESCE(c.delivered_at, NOW())
WHERE c.work_status <> 'COMPLETED'
  AND c.production_order_id IN (
    SELECT id FROM production_order
    WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- ---------------------------------------------------------------------
-- Verificación: debería dar 0 en todas las filas (nada "abierto" queda
-- en el universo de órdenes elegibles). Es solo lectura, corre siempre.
-- ---------------------------------------------------------------------
SELECT 'production_order' AS tabla, COUNT(*) AS pendientes
FROM production_order
WHERE COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
  AND status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
UNION ALL
SELECT 'task', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE')
UNION ALL
SELECT 'production_order_warehouse_unit', COUNT(*)
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND u.receipt_status = 'PENDING'
UNION ALL
SELECT 'production_order_item (sin recibir)', COUNT(*)
FROM production_order_item i
JOIN production_order po ON po.id = i.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND i.warehouse_received_qty < COALESCE(
      (SELECT SUM((kv.value)::numeric)::int
       FROM jsonb_each_text(i.sizes_data::jsonb) AS kv(key, value)),
      i.quantity,
      0
    )
UNION ALL
SELECT 'online_sale', COUNT(*)
FROM online_sale os
JOIN production_order po ON po.id = os.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND os.status NOT IN ('ENVIADO', 'ENTREGADO', 'CANCELADO', 'DEVOLUCION', 'ANULADA')
UNION ALL
SELECT 'product_shipment', COUNT(*)
FROM product_shipment ps
JOIN production_order po ON po.id = ps.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND ps.status NOT IN ('DELIVERED', 'CANCELLED')
UNION ALL
SELECT 'product_distribution', COUNT(*)
FROM product_distribution pd
WHERE pd.id IN (
    SELECT distribution_id FROM production_order
    WHERE distribution_id IS NOT NULL
      AND COALESCE(start_date, created_at::date) <= DATE '2026-05-31'
      AND status NOT IN ('DRAFT', 'CANCELLED')
  )
  AND pd.status <> 'COMPLETED'
UNION ALL
SELECT 'production_order_partial_release', COUNT(*)
FROM production_order_partial_release r
JOIN production_order po ON po.id = r.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND r.status NOT IN ('SHIPPED', 'CANCELLED')
UNION ALL
SELECT 'internal_shipment_request', COUNT(*)
FROM internal_shipment_request isr
JOIN production_order po ON po.id = isr.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND isr.status = 'PENDIENTE'
UNION ALL
SELECT 'production_cincho_day_status', COUNT(*)
FROM production_cincho_day_status c
JOIN production_order po ON po.id = c.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-05-31'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND c.work_status <> 'COMPLETED';
