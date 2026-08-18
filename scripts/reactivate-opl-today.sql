-- =====================================================================
-- Reactivar OPL del día (cerradas por error por close-opl-*.sql)
-- =====================================================================
-- Motivo: el cierre masivo no debía incluir las OPL de hoy; las ventas
-- quedaron ENVIADO y las OP en COMPLETED.
--
-- Alcance (solo OPL del día objetivo):
--   order_type = 'VENTA_EN_LINEA' OR code LIKE 'OPL-%'
--   AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
--         = :target_day
--
-- Qué revierte:
--   1) online_sale ENVIADO → EN_PRODUCCION
--   2) production_order COMPLETED → IN_PROGRESS + limpia warehouse_receipt_closed_at
--   3) product_shipment DELIVERED (ligados) → PENDING (limpia sent_at/received_at del script)
--   4) production_order_partial_release SHIPPED → DRAFT
--   5) unidades RECEIVED el mismo día → PENDING (limpia received_at)
--   6) tareas COMPLETED el mismo día → IN_PROGRESS (limpia completed_at)
--
-- No toca: ENTREGADO / CANCELADO / DEVOLUCION / ANULADA; DRAFT/CANCELLED de OP.
-- No mueve inventario.
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- Día a reactivar (Guatemala). Ajusta si corres otro día.
-- Por defecto: hoy en America/Guatemala.
DO $$ BEGIN END $$; -- noop para editores; la fecha va en CTE abajo

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id, po.code, po.status, po.warehouse_receipt_closed_at,
         COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) AS fecha_ref
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
SELECT 'opl_del_dia' AS concepto, COUNT(*) AS cantidad FROM opl_hoy
UNION ALL
SELECT 'opl_completed', COUNT(*) FROM opl_hoy WHERE status = 'COMPLETED'
UNION ALL
SELECT 'opl_con_recepcion_cerrada', COUNT(*) FROM opl_hoy WHERE warehouse_receipt_closed_at IS NOT NULL
UNION ALL
SELECT 'online_sale_enviado', COUNT(*)
FROM online_sale os
WHERE os.status = 'ENVIADO'
  AND (
    os.production_order_id IN (SELECT id FROM opl_hoy)
    OR os.id IN (
      SELECT poi.online_sale_id FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL AND poi.production_order_id IN (SELECT id FROM opl_hoy)
    )
  )
UNION ALL
SELECT 'tareas_completed_hoy', COUNT(*)
FROM task t
JOIN opl_hoy o ON o.id = t.production_order_id
WHERE t.status = 'COMPLETED'
  AND t.completed_at IS NOT NULL
  AND (t.completed_at AT TIME ZONE 'America/Guatemala')::date = (SELECT target_day FROM params)
UNION ALL
SELECT 'unidades_received_hoy', COUNT(*)
FROM production_order_warehouse_unit u
JOIN opl_hoy o ON o.id = u.production_order_id
WHERE u.receipt_status = 'RECEIVED'
  AND u.received_at IS NOT NULL
  AND (u.received_at AT TIME ZONE 'America/Guatemala')::date = (SELECT target_day FROM params);

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
)
SELECT
    po.id,
    po.code,
    COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) AS fecha_ref,
    po.status,
    po.warehouse_receipt_closed_at,
    (SELECT COUNT(*) FROM online_sale os
     WHERE os.status = 'ENVIADO'
       AND (os.production_order_id = po.id
            OR os.id IN (SELECT poi.online_sale_id FROM production_order_item poi
                         WHERE poi.production_order_id = po.id AND poi.online_sale_id IS NOT NULL))
    ) AS ventas_enviado
FROM production_order po
CROSS JOIN params p
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
ORDER BY po.code;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 1) Ventas ENVIADO → EN_PRODUCCION
UPDATE online_sale os
SET status = 'EN_PRODUCCION'
WHERE os.status = 'ENVIADO'
  AND (
    os.production_order_id IN (SELECT id FROM opl_hoy)
    OR os.id IN (
      SELECT poi.online_sale_id
      FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL
        AND poi.production_order_id IN (SELECT id FROM opl_hoy)
    )
  );

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 2) OP COMPLETED → IN_PROGRESS + abrir recepción bodega
UPDATE production_order po
SET status = CASE WHEN po.status = 'COMPLETED' THEN 'IN_PROGRESS' ELSE po.status END,
    warehouse_receipt_closed_at = NULL
WHERE po.id IN (SELECT id FROM opl_hoy);

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 3) Envios DELIVERED del script → DRAFT
UPDATE product_shipment ps
SET status = 'DRAFT',
    sent_at = NULL,
    received_at = NULL
WHERE ps.status = 'DELIVERED'
  AND (
    ps.production_order_id IN (SELECT id FROM opl_hoy)
    OR ps.partial_release_id IN (
      SELECT r.id FROM production_order_partial_release r
      WHERE r.production_order_id IN (SELECT id FROM opl_hoy)
    )
  );

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 4) Liberaciones parciales SHIPPED → DRAFT
UPDATE production_order_partial_release r
SET status = 'DRAFT'
WHERE r.status = 'SHIPPED'
  AND r.production_order_id IN (SELECT id FROM opl_hoy);

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 5) Unidades marcadas RECEIVED hoy → PENDING
UPDATE production_order_warehouse_unit u
SET receipt_status = 'PENDING',
    received_at = NULL
WHERE u.receipt_status = 'RECEIVED'
  AND u.production_order_id IN (SELECT id FROM opl_hoy)
  AND u.received_at IS NOT NULL
  AND (u.received_at AT TIME ZONE 'America/Guatemala')::date = (SELECT target_day FROM params);

WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
-- 6) Tareas COMPLETED hoy → IN_PROGRESS
UPDATE task t
SET status = 'IN_PROGRESS',
    completed_at = NULL
WHERE t.status = 'COMPLETED'
  AND t.production_order_id IN (SELECT id FROM opl_hoy)
  AND t.completed_at IS NOT NULL
  AND (t.completed_at AT TIME ZONE 'America/Guatemala')::date = (SELECT target_day FROM params);

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
WITH params AS (
  SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date AS target_day
),
opl_hoy AS (
  SELECT po.id, po.code, po.status, po.warehouse_receipt_closed_at
  FROM production_order po
  CROSS JOIN params p
  WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
    AND (
        UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
        OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
    )
    AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) = p.target_day
)
SELECT 'opl_aun_completed' AS check_name, COUNT(*) AS cantidad
FROM opl_hoy WHERE status = 'COMPLETED'
UNION ALL
SELECT 'opl_con_recepcion_cerrada', COUNT(*)
FROM opl_hoy WHERE warehouse_receipt_closed_at IS NOT NULL
UNION ALL
SELECT 'online_sale_aun_enviado', COUNT(*)
FROM online_sale os
WHERE os.status = 'ENVIADO'
  AND (
    os.production_order_id IN (SELECT id FROM opl_hoy)
    OR os.id IN (
      SELECT poi.online_sale_id FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL AND poi.production_order_id IN (SELECT id FROM opl_hoy)
    )
  )
UNION ALL
SELECT 'opl_abiertas_ok', COUNT(*)
FROM opl_hoy WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA');
