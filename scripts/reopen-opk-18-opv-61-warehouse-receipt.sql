-- =====================================================================
-- Reabrir recepción Bodega PT: OPK-18 y OPV-61
-- =====================================================================
-- Motivo: el script anterior solo limpiaba warehouse_receipt_closed_at.
-- Si las piezas ya estaban RECEIVED (p. ej. cierre admin sin stock), la OP
-- no aparece en "Pendientes de recibir".
--
-- Qué hace:
--   1) Limpia warehouse_receipt_closed_at
--   2) Unidades RECEIVED no enviadas → PENDING (limpia received_at)
--   3) Alinea warehouse_received_qty al conteo real de RECEIVED
--
-- Qué NO hace:
--   - No mueve inventario / kardex (no borra PRODUCTION_ENTRY)
--   - No toca REJECTED ni piezas ya shipped
--
-- CUIDADO: si alguna pieza se recibió de verdad (con stock en Bodega PT),
-- al volver a recibirla en la app puede duplicar inventario. Usar solo si
-- el cierre fue administrativo / sin stock.
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================

-- ¿Existe la OP? (por si el código no es exacto)
SELECT id, code, status, warehouse_receipt_closed_at,
       COALESCE(start_date, (created_at AT TIME ZONE 'America/Guatemala')::date) AS fecha_ref
FROM production_order
WHERE UPPER(TRIM(code)) IN ('OPK-18', 'OPV-61')
   OR UPPER(TRIM(code)) LIKE 'OPK-18%'
   OR UPPER(TRIM(code)) LIKE 'OPV-61%'
ORDER BY code;

WITH target AS (
  SELECT po.id, po.code, po.status, po.warehouse_receipt_closed_at,
         COALESCE(po.start_date, (po.created_at AT TIME ZONE 'America/Guatemala')::date) AS fecha_ref
  FROM production_order po
  WHERE UPPER(TRIM(po.code)) IN ('OPK-18', 'OPV-61')
)
SELECT
    t.code,
    t.status,
    t.fecha_ref,
    t.warehouse_receipt_closed_at,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = t.id AND u.receipt_status = 'PENDING') AS unidades_pending,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = t.id AND u.receipt_status = 'RECEIVED'
       AND u.shipped_at IS NULL) AS unidades_received_reabribles,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = t.id AND u.receipt_status = 'RECEIVED'
       AND u.shipped_at IS NOT NULL) AS unidades_received_ya_enviadas,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = t.id AND u.receipt_status = 'REJECTED') AS unidades_rejected,
    (SELECT COALESCE(SUM(i.quantity), 0) FROM production_order_item i
     WHERE i.production_order_id = t.id) AS qty_pedida_items,
    (SELECT COALESCE(SUM(COALESCE(i.warehouse_received_qty, 0)), 0) FROM production_order_item i
     WHERE i.production_order_id = t.id) AS qty_recibida_items
FROM target t
ORDER BY t.code;

-- =====================================================================
-- B) UPDATES — reabre recepción + piezas a PENDING
-- =====================================================================
BEGIN;

-- 1) Abrir recepción en la OP
UPDATE production_order po
SET warehouse_receipt_closed_at = NULL
WHERE UPPER(TRIM(po.code)) IN ('OPK-18', 'OPV-61');

-- 2) RECEIVED (sin enviar) → PENDING
UPDATE production_order_warehouse_unit u
SET receipt_status = 'PENDING',
    received_at = NULL,
    rejection_reason = NULL
WHERE u.receipt_status = 'RECEIVED'
  AND u.shipped_at IS NULL
  AND u.production_order_id IN (
      SELECT id FROM production_order
      WHERE UPPER(TRIM(code)) IN ('OPK-18', 'OPV-61')
  );

-- 3) Alinear warehouse_received_qty al conteo real de RECEIVED (queda 0 si se reabrió todo)
UPDATE production_order_item i
SET warehouse_received_qty = (
    SELECT COUNT(*)::int
    FROM production_order_warehouse_unit u
    WHERE u.production_order_item_id = i.id
      AND u.receipt_status = 'RECEIVED'
)
WHERE i.production_order_id IN (
    SELECT id FROM production_order
    WHERE UPPER(TRIM(code)) IN ('OPK-18', 'OPV-61')
);

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT
    po.code,
    po.warehouse_receipt_closed_at,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = po.id AND u.receipt_status = 'PENDING') AS unidades_pending,
    (SELECT COUNT(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_id = po.id AND u.receipt_status = 'RECEIVED') AS unidades_received
FROM production_order po
WHERE UPPER(TRIM(po.code)) IN ('OPK-18', 'OPV-61')
ORDER BY po.code;
-- Esperado:
--   warehouse_receipt_closed_at IS NULL
--   unidades_pending > 0  → debe aparecer en Bodega PT → Recepción
--
-- En el front: si no la ves, cambia el rango a "Todas las fechas"
-- (filtro por defecto = últimos 30 días).
