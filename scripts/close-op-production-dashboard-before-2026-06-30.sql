-- =====================================================================
-- Cierre de OP de producción (NO OPL) para limpiar dashboard "OP críticas"
-- Corte: fecha de referencia < 2026-06-30 (menores al 30 de junio)
-- =====================================================================
-- Incluye: OPK, OPV, OPC, OPCK, OPI, OPD y demás tipos distintos de OPL.
-- Excluye: VENTA_EN_LINEA / código OPL-* (usar close-opl-through-2026-07-31.sql)
--
-- Por qué salen en el dashboard:
--   status activo (PENDING / IN_PROGRESS / IN_QA / DRAFT…)
--   + razones: Vencida, Sin tareas, Materiales pendientes, etc.
-- Al pasar a COMPLETED dejan de entrar a criticalOrders.
--
-- Sin generar stock (no PRODUCTION_ENTRY / no inventario).
-- Fecha de referencia = COALESCE(start_date, created_at::date)
-- Idempotente.
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    'op_abiertas_dashboard' AS concepto,
    COUNT(*) AS cantidad
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED', 'COMPLETED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
UNION ALL
SELECT 'tareas_abiertas', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE', 'IN_QA')
UNION ALL
SELECT 'sin_materials_consumed', COUNT(*)
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND COALESCE(po.materials_consumed, FALSE) = FALSE;

SELECT
    po.id,
    po.code,
    po.order_type,
    po.status,
    COALESCE(po.start_date, po.created_at::date) AS fecha_ref,
    po.delivery_date,
    po.materials_consumed
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED', 'COMPLETED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
ORDER BY COALESCE(po.start_date, po.created_at::date) DESC, po.id DESC
LIMIT 80;

-- =====================================================================
-- B) UPDATES
-- =====================================================================

-- 1) Unidades bodega PT PENDING -> RECEIVED (sin stock; alinea % recepción)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.receipt_status = 'PENDING'
  AND u.production_order_id IN (
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 2) Alinear warehouse_received_qty (barra % del dashboard)
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
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 3) Tareas abiertas -> COMPLETED + materiales entregados
UPDATE task t
SET status = 'COMPLETED',
    started_at = COALESCE(t.started_at, NOW()),
    completed_at = COALESCE(t.completed_at, NOW()),
    materials_delivered = TRUE,
    materials_delivered_at = COALESCE(t.materials_delivered_at, NOW())
WHERE t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE', 'IN_QA')
  AND t.production_order_id IN (
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 4) OP: materiales consumidos + cierre recepción + COMPLETED
UPDATE production_order po
SET materials_consumed = TRUE,
    materials_consumed_at = COALESCE(po.materials_consumed_at, NOW()),
    warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW())
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  );

UPDATE production_order po
SET status = 'COMPLETED'
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  );

-- 5) Envíos ligados a esas OP -> DELIVERED
UPDATE product_shipment ps
SET status = 'DELIVERED',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = COALESCE(ps.received_at, NOW())
WHERE ps.status NOT IN ('DELIVERED', 'CANCELLED')
  AND (
    ps.production_order_id IN (
      SELECT id FROM production_order po
      WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
        AND po.status NOT IN ('DRAFT', 'CANCELLED')
        AND NOT (
            UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
            OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
        )
    )
    OR ps.distribution_id IN (
      SELECT distribution_id FROM production_order po
      WHERE distribution_id IS NOT NULL
        AND COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
        AND po.status NOT IN ('DRAFT', 'CANCELLED')
        AND NOT (
            UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
            OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
        )
    )
    OR ps.partial_release_id IN (
      SELECT r.id FROM production_order_partial_release r
      WHERE r.production_order_id IN (
        SELECT id FROM production_order po
        WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
          AND po.status NOT IN ('DRAFT', 'CANCELLED')
          AND NOT (
              UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
              OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
          )
      )
    )
  );

-- 6) Distribuciones -> COMPLETED
UPDATE product_distribution pd
SET status = 'COMPLETED'
WHERE pd.status <> 'COMPLETED'
  AND pd.id IN (
    SELECT distribution_id FROM production_order po
    WHERE distribution_id IS NOT NULL
      AND COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 7) Liberaciones parciales -> SHIPPED
UPDATE production_order_partial_release r
SET status = 'SHIPPED'
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND r.production_order_id IN (
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 8) ENVI pendientes -> APROBADA
UPDATE internal_shipment_request isr
SET status = 'APROBADA',
    reviewed_at = COALESCE(isr.reviewed_at, NOW())
WHERE isr.status = 'PENDIENTE'
  AND isr.production_order_id IN (
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- 9) Mesa cinchos -> COMPLETED + entregado
UPDATE production_cincho_day_status c
SET work_status = 'COMPLETED',
    delivered = TRUE,
    delivered_at = COALESCE(c.delivered_at, NOW())
WHERE c.work_status <> 'COMPLETED'
  AND c.production_order_id IN (
    SELECT id FROM production_order po
    WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
      AND po.status NOT IN ('DRAFT', 'CANCELLED')
      AND NOT (
          UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
      )
  );

-- =====================================================================
-- C) VERIFICACIÓN — debe dar 0 en abiertas del corte (no OPL)
-- =====================================================================
SELECT 'production_order_abiertas' AS check_name, COUNT(*) AS restantes
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
UNION ALL
SELECT 'task_abiertas', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE', 'IN_QA')
UNION ALL
SELECT 'materials_consumed_false', COUNT(*)
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) < DATE '2026-06-30'
  AND po.status NOT IN ('DRAFT', 'CANCELLED')
  AND NOT (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND COALESCE(po.materials_consumed, FALSE) = FALSE;
