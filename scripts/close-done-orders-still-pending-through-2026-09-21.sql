-- =====================================================================
-- Cerrar OP "hechas" (avance 100% en bodega) que siguen PENDING/IN_PROGRESS
-- Incluye OPCK (CLIENTE_KIOSKO), OPI (INTERNA), etc. — no solo OPL.
-- Corte fecha OP: COALESCE(start_date, created_at::date) <= 2026-09-21
-- Idempotente.
-- =====================================================================

-- A) PREVIA
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
    ) AS planned_qty,
    SUM(COALESCE(i.warehouse_received_qty, 0)) AS received_qty
  FROM production_order_item i
  GROUP BY i.production_order_id
)
SELECT po.code, po.order_type, po.status,
       COALESCE(po.start_date, po.created_at::date) AS fecha_op,
       p.planned_qty, p.received_qty
FROM production_order po
JOIN planned_by_po p ON p.po_id = po.id
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-21'
  AND COALESCE(p.received_qty, 0) >= COALESCE(p.planned_qty, 0)
  AND COALESCE(p.planned_qty, 0) > 0
ORDER BY fecha_op, po.code;

-- B) UPDATES
BEGIN;

DROP TABLE IF EXISTS tmp_po_done_open;
CREATE TEMP TABLE tmp_po_done_open AS
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
    ) AS planned_qty,
    SUM(COALESCE(i.warehouse_received_qty, 0)) AS received_qty
  FROM production_order_item i
  GROUP BY i.production_order_id
)
SELECT po.id
FROM production_order po
JOIN planned_by_po p ON p.po_id = po.id
WHERE UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-21'
  AND COALESCE(p.received_qty, 0) >= COALESCE(p.planned_qty, 0)
  AND COALESCE(p.planned_qty, 0) > 0;

SELECT COUNT(*) AS op_a_completar FROM tmp_po_done_open;

-- Tareas abiertas de esas OP → COMPLETED
UPDATE task t
SET
  status = 'COMPLETED',
  completed_at = COALESCE(
    t.completed_at,
    (COALESCE(t.scheduled_date, t.created_at::date) + TIME '17:00')
  ),
  actual_duration_minutes = CASE
    WHEN t.estimated_hours IS NOT NULL AND t.estimated_hours > 0
      THEN ROUND(t.estimated_hours * 60)::int
    ELSE t.actual_duration_minutes
  END,
  updated_at = NOW()
WHERE t.production_order_id IN (SELECT id FROM tmp_po_done_open)
  AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED');

-- OP → COMPLETED
UPDATE production_order po
SET status = 'COMPLETED', updated_at = NOW()
WHERE po.id IN (SELECT id FROM tmp_po_done_open);

COMMIT;

-- C) VERIFICACIÓN — esas 3 (y similares) no deben salir
SELECT po.code, po.status, po.order_type
FROM production_order po
WHERE po.code IN ('OPCK-191-03-09-26', 'OPI-16-02-09-26', 'OPI-15-02-09-26');
