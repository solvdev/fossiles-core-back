-- =====================================================================
-- Cierre masivo SOLO OPL (venta en línea) — TODAS excepto exclusiones
-- =====================================================================
-- Solo toca production_order donde:
--   order_type = 'VENTA_EN_LINEA'  OR  code LIKE 'OPL-%'
--
-- EXCLUIDAS (no se cierran):
--   - OPL-1175, OPL-1179, OPL-1180, OPL-1181
--   - OPL del día actual (Guatemala): no cerrar las de hoy
--
-- Sin filtro de fecha histórica: cierra abiertas salvo exclusiones / hoy.
-- Sin generar stock (no PRODUCTION_ENTRY / no inventario).
-- Excluye DRAFT / CANCELLED. Idempotente.
--
-- Si cerraste hoy por error: scripts/reactivate-opl-today.sql
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
--   (verificación: pendientes solo pueden ser exclusiones + hoy)
-- =====================================================================

-- Predicado OPL:
--   UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
--   OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
--
-- Exclusión por número:
--   regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
--     NOT IN ('1175', '1179', '1180', '1181')
--
-- Exclusión hoy (Guatemala):
--   COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
--     < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    'opl_abiertas_a_cerrar' AS concepto,
    COUNT(*) AS cantidad
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
UNION ALL
SELECT 'opl_excluidas_abiertas', COUNT(*)
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
        IN ('1175', '1179', '1180', '1181')
UNION ALL
SELECT 'opl_hoy_excluidas_abiertas', COUNT(*)
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
  )
  AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
        = (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
UNION ALL
SELECT 'tareas_abiertas', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE')
UNION ALL
SELECT 'unidades_PENDING', COUNT(*)
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND u.receipt_status = 'PENDING';

SELECT
    po.id,
    po.code,
    COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) AS fecha_ref,
    po.status,
    po.order_type
FROM production_order po
WHERE po.status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
ORDER BY COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) DESC, po.id DESC
LIMIT 50;

SELECT
    po.id,
    po.code,
    po.status,
    'EXCLUIDA — no se cierra' AS nota
FROM production_order po
WHERE (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND (
    regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
          IN ('1175', '1179', '1180', '1181')
    OR COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
          = (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  )
ORDER BY po.code;

-- =====================================================================
-- B) UPDATES — solo OPL (excepto 1175 / 1179 / 1180 / 1181 y las de hoy)
-- =====================================================================

-- 1) Unidades bodega PT PENDING -> RECEIVED (sin stock)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.receipt_status = 'PENDING'
  AND u.production_order_id IN (
    SELECT id FROM production_order
    WHERE status NOT IN ('DRAFT', 'CANCELLED')
      AND (
          UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
      )
      AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
            NOT IN ('1175', '1179', '1180', '1181')
      AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
            < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  );

-- 2) Alinear cantidad recibida del ítem
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
    WHERE status NOT IN ('DRAFT', 'CANCELLED')
      AND (
          UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
      )
      AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
            NOT IN ('1175', '1179', '1180', '1181')
      AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
            < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  );

-- 3) Tareas abiertas -> COMPLETED
UPDATE task t
SET status = 'COMPLETED',
    started_at = COALESCE(t.started_at, NOW()),
    completed_at = COALESCE(t.completed_at, NOW())
WHERE t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE')
  AND t.production_order_id IN (
    SELECT id FROM production_order
    WHERE status NOT IN ('DRAFT', 'CANCELLED')
      AND (
          UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
      )
      AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
            NOT IN ('1175', '1179', '1180', '1181')
      AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
            < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  );

-- 4) Cerrar recepción + OP COMPLETED
UPDATE production_order po
SET warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW())
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND po.warehouse_receipt_closed_at IS NULL
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date;

UPDATE production_order po
SET status = 'COMPLETED'
WHERE po.status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date;

-- 5) online_sale ligada a OPL -> ENVIADO
UPDATE online_sale os
SET status = 'ENVIADO'
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'CANCELADO', 'DEVOLUCION', 'ANULADA')
  AND (
    os.production_order_id IN (
      SELECT id FROM production_order
      WHERE status NOT IN ('DRAFT', 'CANCELLED')
        AND (
            UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
            OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
        )
        AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
              NOT IN ('1175', '1179', '1180', '1181')
        AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
              < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
    )
    OR os.id IN (
      SELECT poi.online_sale_id
      FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL
        AND poi.production_order_id IN (
          SELECT id FROM production_order
          WHERE status NOT IN ('DRAFT', 'CANCELLED')
            AND (
                UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
                OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
            )
            AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
                  NOT IN ('1175', '1179', '1180', '1181')
            AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
                  < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
        )
    )
  );

-- 6) product_shipment ligado a OPL -> DELIVERED
UPDATE product_shipment ps
SET status = 'DELIVERED',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = COALESCE(ps.received_at, NOW())
WHERE ps.status NOT IN ('DELIVERED', 'CANCELLED')
  AND (
    ps.production_order_id IN (
      SELECT id FROM production_order
      WHERE status NOT IN ('DRAFT', 'CANCELLED')
        AND (
            UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
            OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
        )
        AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
              NOT IN ('1175', '1179', '1180', '1181')
        AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
              < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
    )
    OR ps.partial_release_id IN (
      SELECT r.id FROM production_order_partial_release r
      WHERE r.production_order_id IN (
        SELECT id FROM production_order
        WHERE status NOT IN ('DRAFT', 'CANCELLED')
          AND (
              UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
              OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
          )
          AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
                NOT IN ('1175', '1179', '1180', '1181')
          AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
                < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
      )
    )
  );

-- 7) liberaciones parciales de OPL -> SHIPPED
UPDATE production_order_partial_release r
SET status = 'SHIPPED'
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND r.production_order_id IN (
    SELECT id FROM production_order
    WHERE status NOT IN ('DRAFT', 'CANCELLED')
      AND (
          UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
          OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
      )
      AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
            NOT IN ('1175', '1179', '1180', '1181')
      AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
            < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  );

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
-- production_order pendientes (no exclusión / no hoy): debe ser 0 tras el cierre.
SELECT 'production_order_pendientes_no_excluidas' AS tabla, COUNT(*) AS pendientes
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
UNION ALL
SELECT 'production_order_excluidas_aun_abiertas', COUNT(*)
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
        IN ('1175', '1179', '1180', '1181')
UNION ALL
SELECT 'production_order_hoy_aun_abiertas', COUNT(*)
FROM production_order
WHERE status IN ('PENDING', 'IN_PROGRESS', 'IN_QA')
  AND (
      UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
  )
  AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
        = (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
UNION ALL
SELECT 'task', COUNT(*)
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND t.status IN ('PENDING', 'IN_PROGRESS', 'AWAITING_WAREHOUSE')
UNION ALL
SELECT 'production_order_warehouse_unit', COUNT(*)
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND u.receipt_status = 'PENDING'
UNION ALL
SELECT 'online_sale', COUNT(*)
FROM online_sale os
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'CANCELADO', 'DEVOLUCION', 'ANULADA')
  AND (
    os.production_order_id IN (
      SELECT id FROM production_order
      WHERE status NOT IN ('DRAFT', 'CANCELLED')
        AND (
            UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
            OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
        )
        AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
              NOT IN ('1175', '1179', '1180', '1181')
        AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
              < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
    )
    OR os.id IN (
      SELECT poi.online_sale_id
      FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL
        AND poi.production_order_id IN (
          SELECT id FROM production_order
          WHERE status NOT IN ('DRAFT', 'CANCELLED')
            AND (
                UPPER(TRIM(COALESCE(order_type, ''))) = 'VENTA_EN_LINEA'
                OR UPPER(TRIM(COALESCE(code, ''))) LIKE 'OPL-%'
            )
            AND regexp_replace(UPPER(TRIM(COALESCE(code, ''))), '^OPL-0*', '')
                  NOT IN ('1175', '1179', '1180', '1181')
            AND COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date)
                  < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
        )
    )
  )
UNION ALL
SELECT 'product_shipment', COUNT(*)
FROM product_shipment ps
JOIN production_order po ON po.id = ps.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND ps.status NOT IN ('DELIVERED', 'CANCELLED')
UNION ALL
SELECT 'production_order_partial_release', COUNT(*)
FROM production_order_partial_release r
JOIN production_order po ON po.id = r.production_order_id
WHERE po.status NOT IN ('DRAFT', 'CANCELLED')
  AND (
      UPPER(TRIM(COALESCE(po.order_type, ''))) = 'VENTA_EN_LINEA'
      OR UPPER(TRIM(COALESCE(po.code, ''))) LIKE 'OPL-%'
  )
  AND regexp_replace(UPPER(TRIM(COALESCE(po.code, ''))), '^OPL-0*', '')
        NOT IN ('1175', '1179', '1180', '1181')
  AND COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date)
        < (CURRENT_TIMESTAMP AT TIME ZONE 'America/Guatemala')::date
  AND r.status NOT IN ('SHIPPED', 'CANCELLED');
