-- =============================================================================
-- CORRECCIÓN DE DATOS: piezas huérfanas en RESERVA_LF (OPV-66) — PostgreSQL
-- =============================================================================
-- Qué pasó: el 26/27-oct se recibieron 29 piezas de OPV-66 (unidades 39875-39903), entraron a Bodega PT y se pasaron a
-- RESERVA_LF. El 29-oct se editó la orden (los ítems se volvieron a crear) y las unidades de bodega se regeneraron
-- (ids 40000-40054, todas PENDIENTES). Las unidades viejas se borraron, pero su kardex quedó apuntando a ids que ya no existen:
--   · RESERVA_LF conserva 27 piezas que ninguna unidad reclama (nunca se liberan al cerrar la orden), y
--   · si bodega vuelve a recibir esas piezas, entran otra vez a la reserva (se contarían doble).
--
-- Arreglo ("re-vincular"): a cada pieza huérfana se le asigna una unidad PENDIENTE de la misma orden con el mismo
-- producto, color y talla; esa unidad pasa a RECIBIDA y los movimientos de kardex de la pieza vieja (entrada a PT,
-- salida de PT y entrada a la reserva) apuntan a la unidad nueva. No cambia ningún stock: solo vuelve a casar kardex y unidades.
-- Al cerrar OPV-66 la reserva se libera sola (RESERVE_RELEASE) con esas 27 + las que reciban después.
--
-- SUPUESTO que debe confirmar bodega ANTES de correrlo: esas 27 piezas siguen físicamente en la bodega de Luis Felipe.
-- Si NO están (se entregaron o se perdieron), no use este script: hay que sacarlas de la reserva con una salida
-- (RESERVE_RELEASE) o un ajuste; avíseme y lo preparo.
--
-- Una sola transacción, idempotente (si ya no hay huérfanas no hace nada), con respaldo y deshacer. No toca stock.
-- Uso (psql o DBeaver, en UNA misma sesión): PASO 1 (lectura) → PASO 2 (BEGIN ... sin COMMIT) → PASO 3 (verificar) → COMMIT o ROLLBACK.
-- =============================================================================


-- =============================================================================
-- PASO 1. REVISIONES PREVIAS (solo lectura)
-- =============================================================================

-- 1a. Piezas huérfanas en la reserva (movimiento de kardex cuya unidad ya no existe)
SELECT k.reference_id AS unidad_vieja, k.reference_number, p.code AS producto, k.color_id, COALESCE(k.size_label, '') AS talla,
       k.quantity, k.movement_date
FROM product_inventory_kardex k
JOIN product p ON p.id = k.product_id
LEFT JOIN production_order_warehouse_unit u ON u.id = k.reference_id
WHERE k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
  AND k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.movement_type = 'RESERVE_TRANSFER_IN'
  AND u.id IS NULL
ORDER BY k.reference_id;

-- 1b. Cuántas tienen una unidad pendiente equivalente en su orden (huérfanas vs pendientes por producto/color/talla)
WITH orf AS (
    SELECT DISTINCT k.reference_id AS old_unit, split_part(k.reference_number, '-WH-UNIT-', 1) AS orden_code,
           k.product_id, k.color_id, COALESCE(k.size_label, '') AS talla
    FROM product_inventory_kardex k
    LEFT JOIN production_order_warehouse_unit u ON u.id = k.reference_id
    WHERE k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
      AND k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.movement_type = 'RESERVE_TRANSFER_IN' AND u.id IS NULL
)
SELECT o.orden_code, p.code AS producto, o.color_id, o.talla, COUNT(*) AS huerfanas,
       (SELECT COUNT(*) FROM production_order po
          JOIN production_order_warehouse_unit nu ON nu.production_order_id = po.id AND nu.receipt_status = 'PENDING'
          JOIN production_order_item ni ON ni.id = nu.production_order_item_id
         WHERE po.code = o.orden_code AND ni.product_id = o.product_id AND ni.color_id IS NOT DISTINCT FROM o.color_id
           AND COALESCE(nu.size_key, '') = o.talla) AS pendientes_equivalentes
FROM orf o JOIN product p ON p.id = o.product_id
GROUP BY o.orden_code, p.code, o.product_id, o.color_id, o.talla
ORDER BY 1, 2, 3;
-- Todas las filas deben tener pendientes_equivalentes >= huerfanas; las que no, el script las deja sin tocar.

-- 1c. Reserva actual vs piezas abiertas (la diferencia son las huérfanas)
SELECT (SELECT COALESCE(SUM(quantity), 0) FROM product_inventory_location
         WHERE location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')) AS reserva_actual,
       (SELECT COUNT(*) FROM production_order_warehouse_unit u
          JOIN production_order po ON po.id = u.production_order_id
         WHERE u.receipt_status = 'RECEIVED' AND po.warehouse_receipt_closed_at IS NULL
           AND UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
           AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
           AND EXISTS (SELECT 1 FROM product_inventory_kardex k
                        WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
                          AND k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF'))) AS piezas_abiertas_con_kardex;


-- =============================================================================
-- PASO 2. RESPALDO + APLICACIÓN  (no hace COMMIT: lo decide usted en el PASO 3)
-- =============================================================================
BEGIN;

-- 2a. Emparejar cada pieza huérfana con una unidad pendiente de su orden (la n-ésima de cada producto/color/talla)
DROP TABLE IF EXISTS reserva_fix_20261109_map;
CREATE TEMP TABLE reserva_fix_20261109_map ON COMMIT DROP AS
WITH orf AS (
    SELECT DISTINCT k.reference_id AS old_unit, split_part(k.reference_number, '-WH-UNIT-', 1) AS orden_code,
           k.product_id, k.color_id, COALESCE(k.size_label, '') AS talla
    FROM product_inventory_kardex k
    LEFT JOIN production_order_warehouse_unit u ON u.id = k.reference_id
    WHERE k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
      AND k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.movement_type = 'RESERVE_TRANSFER_IN' AND u.id IS NULL
),
orf_n AS (
    SELECT o.*, ROW_NUMBER() OVER (PARTITION BY o.orden_code, o.product_id, o.color_id, o.talla ORDER BY o.old_unit) AS n
    FROM orf o
),
pend AS (
    SELECT po.code AS orden_code, ni.product_id, ni.color_id, COALESCE(nu.size_key, '') AS talla, nu.id AS new_unit,
           ROW_NUMBER() OVER (PARTITION BY po.code, ni.product_id, ni.color_id, COALESCE(nu.size_key, '') ORDER BY nu.id) AS n
    FROM production_order po
    JOIN production_order_warehouse_unit nu ON nu.production_order_id = po.id AND nu.receipt_status = 'PENDING'
    JOIN production_order_item ni ON ni.id = nu.production_order_item_id
    WHERE po.code IN (SELECT orden_code FROM orf)
)
SELECT o.old_unit, p.new_unit, o.orden_code, o.product_id, o.color_id, o.talla
FROM orf_n o
JOIN pend p ON p.orden_code = o.orden_code AND p.product_id = o.product_id
           AND p.color_id IS NOT DISTINCT FROM o.color_id AND p.talla = o.talla AND p.n = o.n;

-- 2b. Respaldo de lo que se va a modificar (para el DESHACER)
DROP TABLE IF EXISTS reserva_fix_20261109_bk_kardex;
CREATE TABLE reserva_fix_20261109_bk_kardex AS
SELECT k.id, k.reference_id, k.reference_number, m.new_unit
FROM product_inventory_kardex k
JOIN reserva_fix_20261109_map m ON k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = m.old_unit;

DROP TABLE IF EXISTS reserva_fix_20261109_bk_units;
CREATE TABLE reserva_fix_20261109_bk_units AS
SELECT u.* FROM production_order_warehouse_unit u WHERE u.id IN (SELECT new_unit FROM reserva_fix_20261109_map);

-- 2c. Unidad nueva = RECIBIDA (fecha de la entrada original a Bodega PT; si no hay, la de la reserva)
UPDATE production_order_warehouse_unit u
SET receipt_status = 'RECEIVED',
    received_at = COALESCE(
        (SELECT MIN(k.movement_date) FROM product_inventory_kardex k
          WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = m.old_unit AND k.movement_type = 'PRODUCTION_ENTRY'),
        (SELECT MIN(k.movement_date) FROM product_inventory_kardex k
          WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = m.old_unit)),
    rejection_reason = NULL
FROM reserva_fix_20261109_map m
WHERE u.id = m.new_unit AND u.receipt_status = 'PENDING';

-- 2d. El kardex de la pieza vieja (entrada a PT, salida de PT, entrada a reserva) pasa a apuntar a la unidad nueva
UPDATE product_inventory_kardex k
SET reference_id = m.new_unit,
    reference_number = m.orden_code || '-WH-UNIT-' || m.new_unit
FROM reserva_fix_20261109_map m
WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = m.old_unit;


-- =============================================================================
-- PASO 3. VERIFICACIÓN (revise y luego COMMIT; o ROLLBACK;)
-- =============================================================================

-- 3a. Piezas re-vinculadas y huérfanas que quedaron sin tocar (debe ser 0 sin tocar si 1b estaba completo)
SELECT (SELECT COUNT(*) FROM reserva_fix_20261109_bk_kardex) AS filas_kardex_re_vinculadas,
       (SELECT COUNT(*) FROM production_order_warehouse_unit WHERE id IN (SELECT id FROM reserva_fix_20261109_bk_units) AND receipt_status = 'RECEIVED') AS unidades_ahora_recibidas,
       (SELECT COUNT(*) FROM product_inventory_kardex k
          LEFT JOIN production_order_warehouse_unit u ON u.id = k.reference_id
         WHERE k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
           AND k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND u.id IS NULL) AS huerfanas_restantes;

-- 3b. Regla 5 del audit-inventario-invariantes.sql: reserva = piezas abiertas (debe devolver 0 filas)
WITH esperadas AS (
    SELECT i.product_id, i.color_id, COUNT(*)::numeric AS piezas
    FROM production_order po
    JOIN production_order_warehouse_unit u ON u.production_order_id = po.id AND u.receipt_status = 'RECEIVED'
    JOIN production_order_item i ON i.id = u.production_order_item_id
    WHERE UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%' AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
      AND po.warehouse_receipt_closed_at IS NULL
      AND EXISTS (SELECT 1 FROM product_inventory_kardex k
                  WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
                    AND k.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF'))
    GROUP BY 1, 2
),
reserva AS (
    SELECT pil.product_id, pil.color_id, pil.quantity FROM product_inventory_location pil
    WHERE pil.location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')
)
SELECT '5 reserva distinta de piezas abiertas' AS regla, COALESCE(e.product_id, r.product_id) AS product_id,
       COALESCE(e.color_id, r.color_id) AS color_id, COALESCE(e.piezas, 0) AS piezas_abiertas, COALESCE(r.quantity, 0) AS reserva
FROM esperadas e FULL JOIN reserva r ON r.product_id = e.product_id AND COALESCE(r.color_id, 0) = COALESCE(e.color_id, 0)
WHERE COALESCE(e.piezas, 0) <> COALESCE(r.quantity, 0);

-- 3c. El stock no cambió (solo se tocaron referencias): reserva y Bodega PT iguales a antes
SELECT l.code, SUM(pil.quantity) AS unidades
FROM product_inventory_location pil JOIN locations l ON l.id = pil.location_id
WHERE l.code IN ('BODEGA_PT', 'RESERVA_LF') GROUP BY l.code ORDER BY l.code;

-- Si todo está bien:  COMMIT;
-- Si algo no cuadra:  ROLLBACK;


-- =============================================================================
-- DESHACER (solo si ya hizo COMMIT y hay que volver atrás)
-- =============================================================================
--   BEGIN;
--   UPDATE product_inventory_kardex k SET reference_id = b.reference_id, reference_number = b.reference_number
--   FROM reserva_fix_20261109_bk_kardex b WHERE k.id = b.id;
--   UPDATE production_order_warehouse_unit u SET receipt_status = b.receipt_status, received_at = b.received_at,
--          rejection_reason = b.rejection_reason
--   FROM reserva_fix_20261109_bk_units b WHERE u.id = b.id;
--   COMMIT;
-- Las tablas reserva_fix_20261109_bk_* se pueden borrar cuando ya no hagan falta.
