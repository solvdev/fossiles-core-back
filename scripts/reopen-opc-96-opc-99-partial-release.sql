-- =====================================================================
-- OPC-96 y OPC-99: reabrir para editar liberación parcial y reenviar
-- =====================================================================
-- Motivo: se marcaron completadas / liberaciones enviadas por error.
-- Se necesita poder editar qué se envía (liberación parcial) y regenerar el envío.
--
-- Qué hace:
--   1) OP COMPLETED → IN_PROGRESS
--   2) Liberaciones parciales SHIPPED → DRAFT (editables)
--   3) Envios ligados a esas liberaciones → DRAFT (editables / regenerables)
--      (limpia sent_at / received_at)
--   4) Quita marca de despacho en piezas PT (shipment_ref PRODUCT_SHIPMENT)
--
-- Qué NO hace:
--   - No revierte inventario / kardex (si ya salió stock, hay que corregir aparte)
--   - No borra líneas de liberación ni detalles de envío
--   - No toca CxC / customer_account_entry
--
-- Después en la app:
--   1) Abrir OPC-96 / OPC-99 → Liberaciones parciales
--   2) Editar la liberación (cantidades a enviar)
--   3) Confirmar → Generar envío
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    po.id,
    po.code,
    po.status AS op_status,
    po.order_type,
    po.warehouse_receipt_closed_at
FROM production_order po
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
ORDER BY po.code;

SELECT
    po.code,
    r.id AS release_id,
    r.sequence_num,
    r.label,
    r.status AS release_status,
    (SELECT COUNT(*) FROM production_order_partial_release_line l WHERE l.release_id = r.id) AS lineas
FROM production_order po
JOIN production_order_partial_release r ON r.production_order_id = po.id
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
ORDER BY po.code, r.sequence_num;

SELECT
    po.code,
    ps.id AS shipment_id,
    ps.shipment_number,
    ps.status AS shipment_status,
    ps.partial_release_id,
    ps.sent_at,
    ps.received_at
FROM production_order po
JOIN product_shipment ps ON (
    ps.production_order_id = po.id
    OR ps.partial_release_id IN (
        SELECT r.id FROM production_order_partial_release r
        WHERE r.production_order_id = po.id
    )
)
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
  AND UPPER(TRIM(COALESCE(ps.status, ''))) <> 'CANCELLED'
ORDER BY po.code, ps.id;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

-- 1) OP completada por error → IN_PROGRESS
UPDATE production_order po
SET status = CASE WHEN UPPER(TRIM(po.status)) = 'COMPLETED' THEN 'IN_PROGRESS' ELSE po.status END
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99');

-- 2) Liberaciones SHIPPED → DRAFT (para poder editar líneas)
UPDATE production_order_partial_release r
SET status = 'DRAFT',
    updated_at = NOW()
WHERE UPPER(TRIM(r.status)) = 'SHIPPED'
  AND r.production_order_id IN (
      SELECT id FROM production_order WHERE UPPER(TRIM(code)) IN ('OPC-96', 'OPC-99')
  );

-- 3) Envios ligados → DRAFT (editables / listos para regenerar)
UPDATE product_shipment ps
SET status = 'DRAFT',
    sent_at = NULL,
    received_at = NULL,
    updated_at = NOW()
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) <> 'CANCELLED'
  AND (
      ps.production_order_id IN (
          SELECT id FROM production_order WHERE UPPER(TRIM(code)) IN ('OPC-96', 'OPC-99')
      )
      OR ps.partial_release_id IN (
          SELECT r.id
          FROM production_order_partial_release r
          JOIN production_order po ON po.id = r.production_order_id
          WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
      )
  );

-- 4) Quitar marca de despacho en piezas PT de esos envíos
UPDATE production_order_warehouse_unit u
SET shipment_ref_type = NULL,
    shipment_ref_id = NULL,
    shipped_at = NULL,
    shipped_by = NULL
WHERE u.shipment_ref_type = 'PRODUCT_SHIPMENT'
  AND u.shipment_ref_id IN (
      SELECT ps.id
      FROM product_shipment ps
      WHERE ps.production_order_id IN (
            SELECT id FROM production_order WHERE UPPER(TRIM(code)) IN ('OPC-96', 'OPC-99')
        )
         OR ps.partial_release_id IN (
            SELECT r.id
            FROM production_order_partial_release r
            JOIN production_order po ON po.id = r.production_order_id
            WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
        )
  );

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT
    po.code,
    po.status AS op_status,
    r.id AS release_id,
    r.sequence_num,
    r.status AS release_status
FROM production_order po
LEFT JOIN production_order_partial_release r ON r.production_order_id = po.id
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
ORDER BY po.code, r.sequence_num;

SELECT
    po.code,
    ps.id AS shipment_id,
    ps.shipment_number,
    ps.status AS shipment_status,
    ps.partial_release_id,
    ps.sent_at,
    ps.received_at
FROM production_order po
JOIN product_shipment ps ON (
    ps.production_order_id = po.id
    OR ps.partial_release_id IN (
        SELECT r.id FROM production_order_partial_release r
        WHERE r.production_order_id = po.id
    )
)
WHERE UPPER(TRIM(po.code)) IN ('OPC-96', 'OPC-99')
ORDER BY po.code, ps.id;

-- Esperado:
--   op_status = IN_PROGRESS (si estaba COMPLETED)
--   release_status = DRAFT  → editable en Liberaciones parciales
--   shipment_status = DRAFT → se puede editar / confirmar / regenerar envío
