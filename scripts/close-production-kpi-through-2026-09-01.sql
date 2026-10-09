-- =====================================================================
-- Cierre KPI / dashboard producción
-- Corte: fecha de referencia <= 2026-09-01 (1 sep y hacia atrás)
-- =====================================================================
-- Qué hace:
--   1) Unidades bodega PT PENDING -> RECEIVED (sin stock)
--   2) warehouse_received_qty = qty planificada (barra % avance)
--   3) Tareas abiertas -> COMPLETED
--        completed_at = fecha de la tarea (no hoy)
--        SIN inventar actual_duration_minutes (eficiencia queda con datos reales)
--   4) OP: materials_consumed + cierre recepción + COMPLETED
--   5) Envíos / distribuciones / liberaciones / ENVI / cinchos
--
-- Fecha ref OP  = COALESCE(start_date, created_at::date)
-- Fecha ref task = COALESCE(scheduled_date, created_at::date)
-- Sin PRODUCTION_ENTRY / sin inventario.
-- Idempotente. Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT 'op_abiertas_pre_01' AS concepto, COUNT(*) AS cantidad
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
UNION ALL
SELECT 'tareas_abiertas_pre_01', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(t.scheduled_date, t.created_at::date) <= DATE '2026-09-01'
UNION ALL
SELECT 'op_atrasadas_hoy', COUNT(*)
FROM production_order po
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND po.delivery_date IS NOT NULL
  AND po.delivery_date < CURRENT_DATE
UNION ALL
SELECT 'sin_materials_consumed', COUNT(*)
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
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
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
ORDER BY COALESCE(po.start_date, po.created_at::date) DESC, po.id DESC
LIMIT 80;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

-- Temp: OP del corte (activas)
DROP TABLE IF EXISTS tmp_po_cut;
CREATE TEMP TABLE tmp_po_cut AS
SELECT po.id
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT');

SELECT COUNT(*) AS op_en_corte FROM tmp_po_cut;

-- 1) Unidades bodega PT PENDING -> RECEIVED (sin stock; alinea % recepción)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.receipt_status = 'PENDING'
  AND u.production_order_id IN (SELECT id FROM tmp_po_cut);

-- 2) Alinear warehouse_received_qty (barra % del dashboard)
UPDATE production_order_item i
SET warehouse_received_qty = GREATEST(
      COALESCE(i.warehouse_received_qty, 0),
      CASE
        WHEN i.sizes_data IS NOT NULL
         AND btrim(i.sizes_data) NOT IN ('', 'null')
         AND pg_input_is_valid(i.sizes_data, 'jsonb')
         AND jsonb_typeof(i.sizes_data::jsonb) = 'object'
        THEN COALESCE((
               SELECT SUM(CASE WHEN e.value ~ '^[0-9]+(\.[0-9]+)?$' THEN e.value::numeric ELSE 0 END)::int
               FROM jsonb_each_text(i.sizes_data::jsonb) AS e(key, value)
             ), 0)
        ELSE COALESCE(i.quantity, 0)
      END
    ),
    updated_at = NOW()
WHERE i.production_order_id IN (SELECT id FROM tmp_po_cut);

-- 3) Tareas abiertas del corte (por fecha de tarea O por OP del corte) -> COMPLETED
UPDATE task t
SET
  status = 'COMPLETED',
  started_at = COALESCE(
    t.started_at,
    (COALESCE(t.scheduled_date, t.created_at::date) + TIME '08:00')
  ),
  completed_at = COALESCE(
    t.completed_at,
    (COALESCE(t.scheduled_date, t.created_at::date) + TIME '17:00')
  ),
  materials_delivered = TRUE,
  materials_delivered_at = COALESCE(
    t.materials_delivered_at,
    (COALESCE(t.scheduled_date, t.created_at::date) + TIME '17:00')
  ),
  worked_desk = COALESCE(t.worked_desk, t.desk),
  desk = NULL,
  updated_at = NOW()
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND (
    COALESCE(t.scheduled_date, t.created_at::date) <= DATE '2026-09-01'
    OR t.production_order_id IN (SELECT id FROM tmp_po_cut)
  );
-- Nota: no seteamos actual_duration_minutes a propósito.
-- La eficiencia del dashboard solo usa tareas con duración real medida.

-- 4) OP: materiales + cierre recepción
UPDATE production_order po
SET materials_consumed = TRUE,
    materials_consumed_at = COALESCE(po.materials_consumed_at, NOW()),
    warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW()),
    updated_at = NOW()
WHERE po.id IN (SELECT id FROM tmp_po_cut);

-- 5) OP -> COMPLETED (todas las del corte; ya no hay tareas abiertas)
UPDATE production_order po
SET status = 'COMPLETED',
    updated_at = NOW()
WHERE po.id IN (SELECT id FROM tmp_po_cut)
  AND UPPER(COALESCE(po.status, '')) IN ('PENDING', 'IN_PROGRESS', 'IN_QA');

-- 6) Envíos ligados -> DELIVERED
UPDATE product_shipment ps
SET status = 'DELIVERED',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = COALESCE(ps.received_at, NOW())
WHERE ps.status NOT IN ('DELIVERED', 'CANCELLED')
  AND (
    ps.production_order_id IN (SELECT id FROM tmp_po_cut)
    OR ps.distribution_id IN (
      SELECT distribution_id FROM production_order po
      WHERE distribution_id IS NOT NULL
        AND po.id IN (SELECT id FROM tmp_po_cut)
    )
    OR ps.partial_release_id IN (
      SELECT r.id FROM production_order_partial_release r
      WHERE r.production_order_id IN (SELECT id FROM tmp_po_cut)
    )
  );

-- 7) Distribuciones -> COMPLETED
UPDATE product_distribution pd
SET status = 'COMPLETED'
WHERE pd.status <> 'COMPLETED'
  AND pd.id IN (
    SELECT distribution_id FROM production_order po
    WHERE distribution_id IS NOT NULL
      AND po.id IN (SELECT id FROM tmp_po_cut)
  );

-- 8) Liberaciones parciales -> SHIPPED
UPDATE production_order_partial_release r
SET status = 'SHIPPED'
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND r.production_order_id IN (SELECT id FROM tmp_po_cut);

-- 9) ENVI pendientes -> APROBADA
UPDATE internal_shipment_request isr
SET status = 'APROBADA',
    reviewed_at = COALESCE(isr.reviewed_at, NOW())
WHERE isr.status = 'PENDIENTE'
  AND isr.production_order_id IN (SELECT id FROM tmp_po_cut);

-- 10) Mesa cinchos -> COMPLETED + entregado
UPDATE production_cincho_day_status c
SET work_status = 'COMPLETED',
    delivered = TRUE,
    delivered_at = COALESCE(c.delivered_at, NOW())
WHERE c.work_status <> 'COMPLETED'
  AND c.production_order_id IN (SELECT id FROM tmp_po_cut);

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN — idealmente 0 en abiertas del corte
-- =====================================================================
SELECT 'op_abiertas_pre_01' AS check_name, COUNT(*) AS restantes
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
UNION ALL
SELECT 'tareas_abiertas_pre_01', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(t.scheduled_date, t.created_at::date) <= DATE '2026-09-01'
UNION ALL
SELECT 'op_atrasadas_hoy', COUNT(*)
FROM production_order po
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND po.delivery_date IS NOT NULL
  AND po.delivery_date < CURRENT_DATE
UNION ALL
SELECT 'materials_consumed_false', COUNT(*)
FROM production_order po
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) = 'COMPLETED'
  AND COALESCE(po.materials_consumed, FALSE) = FALSE
UNION ALL
SELECT 'warehouse_units_pending', COUNT(*)
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-01'
  AND UPPER(COALESCE(po.status, '')) = 'COMPLETED'
  AND u.receipt_status = 'PENDING';
