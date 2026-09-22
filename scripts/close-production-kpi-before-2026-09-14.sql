-- =====================================================================
-- Fase 0: saneamiento KPI dashboard producción
-- Corte: referencia < 2026-09-14
-- =====================================================================
-- Completa tareas abiertas pre-14 (con o sin mesa) → COMPLETED
--   completed_at = fecha de la tarea (no hoy)
-- Completa OP elegibles → COMPLETED + warehouse_received_qty = planned
-- Eficiencia pre-14: actual_duration_minutes = estimated_hours * 60
--   (asume que duraron lo planificado → 100% en el promedio histórico)
-- Idempotente. Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT 'tareas_abiertas_pre_14' AS concepto, COUNT(*) AS cantidad
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(t.scheduled_date, t.created_at::date) < DATE '2026-09-14'
UNION ALL
SELECT 'op_activas_pre_14', COUNT(*)
FROM production_order po
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(po.start_date, po.created_at::date) < DATE '2026-09-14'
UNION ALL
SELECT 'op_atrasadas_hoy', COUNT(*)
FROM production_order po
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND po.delivery_date IS NOT NULL
  AND po.delivery_date < CURRENT_DATE
UNION ALL
SELECT 'eficiencia_pre_14_fuera_de_plan', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.estimated_hours IS NOT NULL
  AND t.estimated_hours > 0
  AND COALESCE(t.completed_at, t.scheduled_date::timestamp, t.created_at)
      < TIMESTAMP '2026-09-14'
  AND (
    t.actual_duration_minutes IS NULL
    OR t.actual_duration_minutes <= 0
    OR t.actual_duration_minutes <> ROUND(t.estimated_hours * 60)::int
  );

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

UPDATE task t
SET
  status = 'COMPLETED',
  completed_at = COALESCE(
    t.completed_at,
    (COALESCE(t.scheduled_date, t.created_at::date) + TIME '17:00')
  ),
  updated_at = NOW()
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(t.scheduled_date, t.created_at::date) < DATE '2026-09-14';

DROP TABLE IF EXISTS tmp_eligible_po;
CREATE TEMP TABLE tmp_eligible_po AS
WITH planned_by_po AS (
  SELECT
    i.production_order_id AS po_id,
    SUM(
      CASE
        WHEN i.sizes_data IS NOT NULL
         AND btrim(i.sizes_data) NOT IN ('', 'null')
         AND pg_input_is_valid(i.sizes_data, 'jsonb')
         AND jsonb_typeof(i.sizes_data::jsonb) = 'object'
        THEN COALESCE((
               SELECT SUM(CASE WHEN e.value ~ '^[0-9]+$' THEN e.value::int ELSE 0 END)
               FROM jsonb_each_text(i.sizes_data::jsonb) AS e(key, value)
             ), 0)
        ELSE COALESCE(i.quantity, 0)
      END
    ) AS planned_qty
  FROM production_order_item i
  GROUP BY i.production_order_id
),
done_by_po AS (
  SELECT t.production_order_id AS po_id,
         SUM(COALESCE(t.quantity, 0)) AS completed_qty
  FROM task t
  WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  GROUP BY t.production_order_id
)
SELECT po.id
FROM production_order po
JOIN planned_by_po p ON p.po_id = po.id
LEFT JOIN done_by_po d ON d.po_id = po.id
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(po.start_date, po.created_at::date) < DATE '2026-09-14'
  AND NOT EXISTS (
    SELECT 1 FROM task t
    WHERE t.production_order_id = po.id
      AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  )
  AND COALESCE(d.completed_qty, 0) >= COALESCE(p.planned_qty, 0);

SELECT COUNT(*) AS op_a_completar FROM tmp_eligible_po;

UPDATE production_order_item i
SET
  warehouse_received_qty = CASE
    WHEN i.sizes_data IS NOT NULL
     AND btrim(i.sizes_data) NOT IN ('', 'null')
     AND pg_input_is_valid(i.sizes_data, 'jsonb')
     AND jsonb_typeof(i.sizes_data::jsonb) = 'object'
    THEN COALESCE((
           SELECT SUM(CASE WHEN e.value ~ '^[0-9]+$' THEN e.value::int ELSE 0 END)
           FROM jsonb_each_text(i.sizes_data::jsonb) AS e(key, value)
         ), 0)
    ELSE COALESCE(i.quantity, 0)
  END,
  updated_at = NOW()
WHERE i.production_order_id IN (SELECT id FROM tmp_eligible_po);

UPDATE production_order po
SET status = 'COMPLETED', updated_at = NOW()
WHERE po.id IN (SELECT id FROM tmp_eligible_po);

-- Eficiencia: pre-14 asume duración = plan (100%)
UPDATE task t
SET
  actual_duration_minutes = ROUND(t.estimated_hours * 60)::int,
  updated_at = NOW()
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.estimated_hours IS NOT NULL
  AND t.estimated_hours > 0
  AND COALESCE(t.completed_at, t.scheduled_date::timestamp, t.created_at)
      < TIMESTAMP '2026-09-14'
  AND (
    t.actual_duration_minutes IS NULL
    OR t.actual_duration_minutes <= 0
    OR t.actual_duration_minutes <> ROUND(t.estimated_hours * 60)::int
  );

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT 'tareas_abiertas_pre_14' AS concepto, COUNT(*) AS cantidad
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND COALESCE(t.scheduled_date, t.created_at::date) < DATE '2026-09-14'
UNION ALL
SELECT 'op_atrasadas_hoy', COUNT(*)
FROM production_order po
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND po.delivery_date IS NOT NULL
  AND po.delivery_date < CURRENT_DATE
UNION ALL
SELECT 'tareas_atrasadas', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND t.delivery_date IS NOT NULL
  AND t.delivery_date < CURRENT_DATE
UNION ALL
SELECT 'sin_mesa', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
  AND (t.desk IS NULL OR t.desk <= 0)
UNION ALL
SELECT 'eficiencia_pre_14_fuera_de_plan', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.estimated_hours IS NOT NULL
  AND t.estimated_hours > 0
  AND COALESCE(t.completed_at, t.scheduled_date::timestamp, t.created_at)
      < TIMESTAMP '2026-09-14'
  AND (
    t.actual_duration_minutes IS NULL
    OR t.actual_duration_minutes <= 0
    OR t.actual_duration_minutes <> ROUND(t.estimated_hours * 60)::int
  )
UNION ALL
SELECT 'eficiencia_con_tiempo_desde_14', COUNT(*)
FROM task t
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.actual_duration_minutes IS NOT NULL
  AND t.actual_duration_minutes > 0
  AND t.estimated_hours IS NOT NULL
  AND t.estimated_hours > 0
  AND COALESCE(t.completed_at, t.scheduled_date::timestamp, t.created_at)
      >= TIMESTAMP '2026-09-14';
