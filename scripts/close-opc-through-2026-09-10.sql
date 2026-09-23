-- =====================================================================
-- Cerrar TODAS las OPC (CINCHOS / CINCHOS_FOSSILES / CINCHOS_MARCAS
--   o código OPC-/OPCF-/OPCM-) con fecha <= 2026-09-10 (incluye el 10).
-- Tareas abiertas → COMPLETED (duración = estimado si hay)
-- Ítems → warehouse_received_qty = planificado
-- OP → COMPLETED
-- Fecha OP: COALESCE(start_date, created_at::date)
-- Idempotente. Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
DROP TABLE IF EXISTS tmp_opc_close;
CREATE TEMP TABLE tmp_opc_close AS
SELECT po.id
FROM production_order po
WHERE (
    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
  )
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-10';

SELECT 'opc_en_rango_hasta_10' AS concepto, COUNT(*) AS cantidad
FROM tmp_opc_close
UNION ALL
SELECT 'opc_activas_hasta_10', COUNT(*)
FROM production_order po
WHERE po.id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
UNION ALL
SELECT 'tareas_abiertas_esas_opc', COUNT(*)
FROM task t
WHERE t.production_order_id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
UNION ALL
SELECT 'unidades_pending_esas_opc', COALESCE(SUM(COALESCE(t.quantity, 0)), 0)
FROM task t
WHERE t.production_order_id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(t.status, '')) = 'PENDING';

SELECT po.code, po.order_type, po.status,
       COALESCE(po.start_date, po.created_at::date) AS fecha_op,
       COUNT(t.id) FILTER (
         WHERE UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
       ) AS tareas_abiertas,
       COALESCE(SUM(t.quantity) FILTER (
         WHERE UPPER(COALESCE(t.status, '')) = 'PENDING'
       ), 0) AS uds_pending
FROM production_order po
LEFT JOIN task t ON t.production_order_id = po.id
WHERE po.id IN (SELECT id FROM tmp_opc_close)
  AND (
    UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
    OR EXISTS (
      SELECT 1 FROM task tx
      WHERE tx.production_order_id = po.id
        AND UPPER(COALESCE(tx.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
    )
  )
GROUP BY po.id, po.code, po.order_type, po.status,
         COALESCE(po.start_date, po.created_at::date)
ORDER BY fecha_op, po.code;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

DROP TABLE IF EXISTS tmp_opc_close;
CREATE TEMP TABLE tmp_opc_close AS
SELECT po.id
FROM production_order po
WHERE (
    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
  )
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-10';

SELECT COUNT(*) AS opc_en_rango FROM tmp_opc_close;

SELECT COUNT(*) AS tareas_abiertas_a_cerrar
FROM task t
WHERE t.production_order_id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED');

-- 1) Tareas abiertas → COMPLETED (aunque la OP ya esté COMPLETED)
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
WHERE t.production_order_id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED');

-- 2) Received pleno
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
WHERE i.production_order_id IN (SELECT id FROM tmp_opc_close);

-- 3) OP activas → COMPLETED
UPDATE production_order po
SET status = 'COMPLETED', updated_at = NOW()
WHERE po.id IN (SELECT id FROM tmp_opc_close)
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED');

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN — debe dar 0 en abiertas
-- =====================================================================
SELECT 'opc_activas_hasta_10' AS concepto, COUNT(*) AS cantidad
FROM production_order po
WHERE (
    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
  )
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-10'
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED')
UNION ALL
SELECT 'tareas_abiertas_opc_hasta_10', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE (
    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
  )
  AND COALESCE(po.start_date, po.created_at::date) <= DATE '2026-09-10'
  AND UPPER(COALESCE(t.status, '')) NOT IN ('COMPLETED', 'CANCELLED');
