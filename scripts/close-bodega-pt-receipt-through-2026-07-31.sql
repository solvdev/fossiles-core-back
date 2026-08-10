-- =====================================================================
-- Cierre administrativo de recepción Bodega PT (sin generar stock)
-- Corte: órdenes con fecha <= 2026-06-30 (último día de julio)
-- =====================================================================
-- Qué hace:
--   1) Piezas PENDING -> RECEIVED (NO crea PRODUCTION_ENTRY / NO mueve stock)
--   2) Alinea warehouse_received_qty del ítem con lo pedido
--   3) Cierra warehouse_receipt_closed_at en la OP
--   4) Completa tareas AWAITING_WAREHOUSE de esas OP (sin inventariar)
--
-- Qué NO hace:
--   - No inserta kardex PRODUCTION_ENTRY
--   - No incrementa product_inventory / Bodega PT
--   - No marca online_sale ENVIADO ni envíos DELIVERED
--
-- Fecha de referencia = COALESCE(start_date, created_at::date)
-- (igual que la fecha que muestra el front en el código de la OP).
--
-- Idempotente: se puede correr varias veces.
-- Flujo recomendado:
--   A) Correr la sección PREVIA (solo SELECT) y revisar conteos
--   B) Correr los UPDATE
--   C) Correr la verificación final (debe dar 0 pendientes de recepción)
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura (correr primero)
-- =====================================================================
SELECT
    'ordenes_elegibles' AS concepto,
    COUNT(*) AS cantidad
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
UNION ALL
SELECT
    'unidades_PENDING',
    COUNT(*)
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND u.receipt_status = 'PENDING'
UNION ALL
SELECT
    'ordenes_con_recepcion_abierta_o_pendiente',
    COUNT(DISTINCT po.id)
FROM production_order po
LEFT JOIN production_order_item i ON i.production_order_id = po.id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      po.warehouse_receipt_closed_at IS NULL
      OR COALESCE(i.warehouse_received_qty, 0) < COALESCE(
            (SELECT SUM((kv.value)::numeric)::int
             FROM jsonb_each_text(NULLIF(i.sizes_data::text, '')::jsonb) AS kv(key, value)
             WHERE (kv.value)::numeric > 0),
            i.quantity,
            0
         )
  );

-- Muestra (opcional): primeras OPL/OP que saldrán de "Pendientes de recibir"
SELECT
    po.id,
    po.code,
    COALESCE(po.start_date, po.created_at::date) AS fecha_ref,
    po.status,
    po.order_type,
    po.warehouse_receipt_closed_at,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = po.id AND u.receipt_status = 'PENDING') AS unidades_pending
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      po.warehouse_receipt_closed_at IS NULL
      OR EXISTS (
          SELECT 1 FROM production_order_warehouse_unit u
          WHERE u.production_order_id = po.id AND u.receipt_status = 'PENDING'
      )
  )
ORDER BY COALESCE(po.start_date, po.created_at::date) DESC, po.id DESC
LIMIT 50;

-- =====================================================================
-- B) UPDATES — recepción administrativa sin stock
-- =====================================================================

-- 1) Unidades PENDING -> RECEIVED (sin kardex / sin inventario)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.receipt_status = 'PENDING'
  AND u.production_order_id IN (
      SELECT id FROM production_order
      WHERE COALESCE(start_date, created_at::date) <= DATE '2026-06-30'
        AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- 2) Alinear cantidad recibida del ítem (para que el front deje de mostrar "por recibir")
--    Pedido = suma positiva de sizes_data si existe; si no, quantity.
UPDATE production_order_item i
SET warehouse_received_qty = GREATEST(
        COALESCE(i.warehouse_received_qty, 0),
        COALESCE(
            (SELECT SUM((kv.value)::numeric)::int
             FROM jsonb_each_text(NULLIF(i.sizes_data::text, '')::jsonb) AS kv(key, value)
             WHERE (kv.value)::numeric > 0),
            i.quantity,
            0
        )
    )
WHERE i.production_order_id IN (
      SELECT id FROM production_order
      WHERE COALESCE(start_date, created_at::date) <= DATE '2026-06-30'
        AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- 3) Cerrar recepción de bodega en la OP
UPDATE production_order po
SET warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW())
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND po.warehouse_receipt_closed_at IS NULL;

-- 4) Tareas que seguían "pendiente bodega PT" -> COMPLETED (sin inventariar)
UPDATE task t
SET status = 'COMPLETED',
    started_at = COALESCE(t.started_at, NOW()),
    completed_at = COALESCE(t.completed_at, NOW())
WHERE t.status = 'AWAITING_WAREHOUSE'
  AND t.production_order_id IN (
      SELECT id FROM production_order
      WHERE COALESCE(start_date, created_at::date) <= DATE '2026-06-30'
        AND status NOT IN ('DRAFT', 'CANCELLED')
  );

-- =====================================================================
-- C) VERIFICACIÓN — debe dar 0 en pendientes de recepción del corte
-- =====================================================================
SELECT 'unidades_PENDING' AS check_name, COUNT(*) AS restantes
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND u.receipt_status = 'PENDING'
UNION ALL
SELECT 'ops_sin_cierre_recepcion', COUNT(*)
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND po.warehouse_receipt_closed_at IS NULL
UNION ALL
SELECT 'items_con_recibido_menor_pedido', COUNT(*)
FROM production_order_item i
JOIN production_order po ON po.id = i.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND COALESCE(i.warehouse_received_qty, 0) < COALESCE(
        (SELECT SUM((kv.value)::numeric)::int
         FROM jsonb_each_text(NULLIF(i.sizes_data::text, '')::jsonb) AS kv(key, value)
         WHERE (kv.value)::numeric > 0),
        i.quantity,
        0
      )
UNION ALL
SELECT 'tareas_AWAITING_WAREHOUSE', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND t.status = 'AWAITING_WAREHOUSE'
;
