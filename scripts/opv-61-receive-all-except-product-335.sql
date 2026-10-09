-- =====================================================================
-- OPV-61: recibir todo EXCEPTO product_id = 335 (queda pendiente)
-- =====================================================================
-- Qué hace:
--   1) Abre recepción (warehouse_receipt_closed_at = NULL)
--   2) Unidades del producto 335 → PENDING
--   3) Unidades del resto de productos → RECEIVED (sin kardex / sin stock)
--   4) Alinea warehouse_received_qty por ítem
--
-- Qué NO hace:
--   - No inserta PRODUCTION_ENTRY ni mueve inventario
--   - No toca piezas ya enviadas (shipped_at IS NOT NULL)
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    po.id AS production_order_id,
    po.code,
    po.status,
    po.warehouse_receipt_closed_at,
    i.id AS item_id,
    i.product_id,
    i.quantity AS qty_pedida,
    COALESCE(i.warehouse_received_qty, 0) AS qty_recibida_item,
    COUNT(*) FILTER (WHERE u.receipt_status = 'PENDING') AS unidades_pending,
    COUNT(*) FILTER (WHERE u.receipt_status = 'RECEIVED') AS unidades_received,
    COUNT(*) FILTER (WHERE u.receipt_status = 'REJECTED') AS unidades_rejected
FROM production_order po
JOIN production_order_item i ON i.production_order_id = po.id
LEFT JOIN production_order_warehouse_unit u ON u.production_order_item_id = i.id
WHERE UPPER(TRIM(po.code)) = 'OPV-61'
GROUP BY po.id, po.code, po.status, po.warehouse_receipt_closed_at,
         i.id, i.product_id, i.quantity, i.warehouse_received_qty
ORDER BY i.product_id, i.id;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

-- 1) Mantener recepción abierta
UPDATE production_order po
SET warehouse_receipt_closed_at = NULL
WHERE UPPER(TRIM(po.code)) = 'OPV-61';

-- 2) Producto 335 → PENDING (lo único que debe faltar por recibir)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'PENDING',
    received_at = NULL,
    rejection_reason = NULL
WHERE u.shipped_at IS NULL
  AND u.receipt_status IS DISTINCT FROM 'REJECTED'
  AND u.production_order_item_id IN (
      SELECT i.id
      FROM production_order_item i
      JOIN production_order po ON po.id = i.production_order_id
      WHERE UPPER(TRIM(po.code)) = 'OPV-61'
        AND i.product_id = 335
  );

-- 3) Resto de productos → RECEIVED (sin inventario)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(u.received_at, NOW())
WHERE u.shipped_at IS NULL
  AND u.receipt_status IS DISTINCT FROM 'REJECTED'
  AND u.production_order_item_id IN (
      SELECT i.id
      FROM production_order_item i
      JOIN production_order po ON po.id = i.production_order_id
      WHERE UPPER(TRIM(po.code)) = 'OPV-61'
        AND i.product_id IS DISTINCT FROM 335
  );

-- 4) Alinear warehouse_received_qty al conteo de RECEIVED
UPDATE production_order_item i
SET warehouse_received_qty = (
    SELECT COUNT(*)::int
    FROM production_order_warehouse_unit u
    WHERE u.production_order_item_id = i.id
      AND u.receipt_status = 'RECEIVED'
)
WHERE i.production_order_id IN (
    SELECT id FROM production_order WHERE UPPER(TRIM(code)) = 'OPV-61'
);

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT
    i.product_id,
    i.quantity AS qty_pedida,
    COALESCE(i.warehouse_received_qty, 0) AS qty_recibida_item,
    COUNT(*) FILTER (WHERE u.receipt_status = 'PENDING') AS unidades_pending,
    COUNT(*) FILTER (WHERE u.receipt_status = 'RECEIVED') AS unidades_received
FROM production_order po
JOIN production_order_item i ON i.production_order_id = po.id
LEFT JOIN production_order_warehouse_unit u ON u.production_order_item_id = i.id
WHERE UPPER(TRIM(po.code)) = 'OPV-61'
GROUP BY i.id, i.product_id, i.quantity, i.warehouse_received_qty
ORDER BY i.product_id;

-- Esperado:
--   product_id = 335  → unidades_pending = qty pedida, received = 0
--   otros product_id  → unidades_pending = 0, received = qty pedida
--   OP con warehouse_receipt_closed_at IS NULL → aparece en Pendientes
--     mostrando solo el faltante (producto 335) en la UI de recepción.
