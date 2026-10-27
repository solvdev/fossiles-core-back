-- =============================================================================
-- CORRECCIÓN DE DATOS: reserva de Luis Felipe + origen de salidas de OP a pedido — PostgreSQL
-- =============================================================================
-- Acompaña al cambio de código "RESERVA_LF / DispatchSource". Corre DESPUÉS de desplegar el back.
--
--   A) OPV/OPC de Luis Felipe recibidas en Bodega PT desde el 15-oct: las pasa a la ubicación
--      RESERVA_LF (RESERVE_TRANSFER_OUT en PT / RESERVE_TRANSFER_IN en la reserva) y, si la recepción
--      de la orden ya está cerrada, las entrega (RESERVE_RELEASE) y marca la pieza como VENDOR_DELIVERY.
--      Las piezas que Bodega PT ya no tiene (PT en 0 para ese producto/color) NO se tocan: quedan
--      listadas en "pendientes" para revisarlas a mano.
--   B) Salidas de OP a pedido (envío de OPCK/OPI/OPC no LF y despacho de venta en línea OPL) que
--      salieron de Devoluciones aunque la pieza entró a PT: devuelve la unidad a Devoluciones y la
--      descuenta de PT (ORIGIN_FIX_IN / ORIGIN_FIX_OUT). Solo si PT tiene la unidad.
--
-- Todo en UNA transacción, idempotente (se puede correr dos veces), y deja el kardex encadenado
-- (quantity_before/after). No toca kioskos ni lotes FIFO de costo.
--
-- Uso (psql o DBeaver, en UNA misma sesión):
--   PASO 1  Revisiones previas (solo lectura).
--   PASO 2  Respaldo + aplicación (BEGIN ... sin COMMIT).
--   PASO 3  Verificación: revise y luego COMMIT; (o ROLLBACK;).
-- Deshacer después del COMMIT: ver la sección DESHACER al final.
-- =============================================================================


-- =============================================================================
-- PASO 1. REVISIONES PREVIAS (solo lectura)
-- =============================================================================

-- 1a. Ubicaciones
SELECT id, code, name, categoria FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF');

-- 1b. Piezas de OPV/OPC de Luis Felipe que están contadas en Bodega PT (candidatas a pasar a reserva)
SELECT po.code AS orden, (po.warehouse_receipt_closed_at IS NOT NULL) AS recepcion_cerrada, COUNT(*) AS piezas,
       COUNT(*) FILTER (WHERE COALESCE(u.size_key, '') <> '') AS con_talla
FROM production_order po
JOIN production_order_warehouse_unit u ON u.production_order_id = po.id AND u.receipt_status = 'RECEIVED'
JOIN product_inventory_kardex k ON k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
     AND k.movement_type = 'PRODUCTION_ENTRY' AND k.location_id = (SELECT id FROM locations WHERE code = 'BODEGA_PT')
WHERE UPPER(po.seller_name) LIKE '%LUIS FELIPE%' AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
  AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex m
                  WHERE m.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND m.reference_id = u.id
                    AND m.movement_type IN ('RESERVE_TRANSFER_IN', 'RESERVE_RELEASE'))
GROUP BY po.code, po.warehouse_receipt_closed_at
ORDER BY po.code;
-- Si "con_talla" sale > 0 las piezas tienen talla: el script las maneja, pero revise el resultado en el PASO 3.

-- 1c. Salidas de OP a pedido que salieron de Devoluciones (candidatas a devolver a Devoluciones)
SELECT k.id AS kardex_id, k.movement_type, p.code AS producto, c.name AS color, k.size_label AS talla,
       -k.quantity AS unidades, k.reference_number, po.code AS orden
FROM product_inventory_kardex k
JOIN product p ON p.id = k.product_id
LEFT JOIN colors c ON c.id = k.color_id
LEFT JOIN product_shipment s ON k.reference_type = 'SHIPMENT' AND s.id = k.reference_id
LEFT JOIN production_order po ON po.id = s.production_order_id
WHERE k.location_id = (SELECT id FROM locations WHERE code = 'DEVOLUCION') AND k.quantity < 0
  AND k.movement_date >= '2026-10-15'
  AND (k.movement_type = 'ONLINE_SALE_DISPATCH'
       OR (po.order_type IN ('CLIENTE_KIOSKO', 'INTERNA') OR po.order_type LIKE 'CINCHOS%')
          AND UPPER(COALESCE(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%')
ORDER BY k.id;

-- 1d. Estado actual de las tres ubicaciones (para comparar con el PASO 3)
SELECT l.code, COUNT(pil.id) AS filas, COALESCE(SUM(pil.quantity), 0) AS unidades
FROM locations l LEFT JOIN product_inventory_location pil ON pil.location_id = l.id
WHERE l.code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF') GROUP BY l.code ORDER BY l.code;


-- =============================================================================
-- PASO 2. RESPALDO Y APLICACIÓN (una sola transacción)
-- =============================================================================
BEGIN;

INSERT INTO locations (code, name, categoria)
SELECT 'RESERVA_LF', 'Reserva Luis Felipe (vendido)', 'RESERVA'
WHERE NOT EXISTS (SELECT 1 FROM locations WHERE code = 'RESERVA_LF');

DROP TABLE IF EXISTS inv_fix_20261026_bk_stock;
CREATE TABLE inv_fix_20261026_bk_stock AS
SELECT pil.* FROM product_inventory_location pil
WHERE pil.location_id IN (SELECT id FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF'));

DROP TABLE IF EXISTS inv_fix_20261026_pendientes;
CREATE TABLE inv_fix_20261026_pendientes (
    motivo text, unidad_id bigint, orden text, kardex_id bigint, producto_id bigint, color_id bigint,
    talla text, unidades numeric
);

-- Mueve una cantidad en una ubicación (con talla opcional) y deja la fila de kardex encadenada.
CREATE OR REPLACE FUNCTION pg_temp.inv_move(
    p_product bigint, p_color bigint, p_loc bigint, p_delta numeric, p_size text,
    p_mtype text, p_reftype text, p_refid bigint, p_refnum text, p_desc text, p_line bigint
) RETURNS void AS $$
DECLARE
    v_row product_inventory_location%ROWTYPE;
    v_before numeric;
    v_after numeric;
    v_sizes jsonb;
    v_cur numeric;
BEGIN
    SELECT * INTO v_row FROM product_inventory_location
    WHERE product_id = p_product AND location_id = p_loc AND color_id IS NOT DISTINCT FROM p_color
    FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO product_inventory_location (product_id, location_id, color_id, quantity, created_at, updated_at)
        VALUES (p_product, p_loc, p_color, 0, now(), now())
        RETURNING * INTO v_row;
    END IF;
    v_before := v_row.quantity;
    v_after := v_before + p_delta;
    IF v_after < 0 THEN
        RAISE EXCEPTION 'Stock negativo (% -> %) producto % color % ubicación %', v_before, v_after, p_product, p_color, p_loc;
    END IF;
    v_sizes := CASE WHEN v_row.sizes_data IS NULL OR v_row.sizes_data = '' THEN NULL ELSE v_row.sizes_data::jsonb END;
    IF p_size IS NOT NULL AND p_size <> '' THEN
        v_cur := COALESCE((v_sizes ->> p_size)::numeric, 0);
        IF v_cur + p_delta < 0 THEN
            RAISE EXCEPTION 'Talla % negativa (% -> %) producto % ubicación %', p_size, v_cur, v_cur + p_delta, p_product, p_loc;
        END IF;
        v_sizes := jsonb_set(COALESCE(v_sizes, '{}'::jsonb), ARRAY[p_size], to_jsonb(v_cur + p_delta), true);
    END IF;
    UPDATE product_inventory_location
       SET quantity = v_after,
           sizes_data = CASE WHEN v_sizes IS NULL THEN sizes_data ELSE v_sizes::text END,
           updated_at = now()
     WHERE id = v_row.id;
    INSERT INTO product_inventory_kardex (created_at, description, location_id, movement_date, movement_type,
                                          product_id, quantity, quantity_after, quantity_before,
                                          reference_id, reference_number, reference_type, color_id,
                                          size_label, reference_line_id)
    VALUES (now(), p_desc, p_loc, now(), p_mtype, p_product, p_delta, v_after, v_before,
            p_refid, p_refnum, p_reftype, p_color, NULLIF(p_size, ''), p_line);
END;
$$ LANGUAGE plpgsql;

-- Cantidad disponible (con talla opcional) en una ubicación.
CREATE OR REPLACE FUNCTION pg_temp.inv_available(p_product bigint, p_color bigint, p_loc bigint, p_size text)
RETURNS numeric AS $$
DECLARE v_row product_inventory_location%ROWTYPE;
BEGIN
    SELECT * INTO v_row FROM product_inventory_location
    WHERE product_id = p_product AND location_id = p_loc AND color_id IS NOT DISTINCT FROM p_color;
    IF NOT FOUND THEN RETURN 0; END IF;
    IF p_size IS NOT NULL AND p_size <> '' AND v_row.sizes_data IS NOT NULL AND v_row.sizes_data <> '' THEN
        RETURN COALESCE((v_row.sizes_data::jsonb ->> p_size)::numeric, 0);
    END IF;
    RETURN v_row.quantity;
END;
$$ LANGUAGE plpgsql;

-- ----------------------------------------------------------------------------
-- A) OPV/OPC de Luis Felipe: PT -> RESERVA_LF, y entrega si la recepción está cerrada
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_pt bigint := (SELECT id FROM locations WHERE code = 'BODEGA_PT');
    v_res bigint := (SELECT id FROM locations WHERE code = 'RESERVA_LF');
    r record;
    v_size text;
    v_moved int := 0;
    v_released int := 0;
    v_skipped int := 0;
BEGIN
    FOR r IN
        SELECT u.id AS unit_id, po.id AS po_id, po.code AS po_code,
               (po.warehouse_receipt_closed_at IS NOT NULL) AS closed,
               i.product_id, i.color_id, NULLIF(u.size_key, '') AS size_key, k.id AS entry_kardex_id,
               u.unit_label
        FROM production_order po
        JOIN production_order_warehouse_unit u ON u.production_order_id = po.id AND u.receipt_status = 'RECEIVED'
        JOIN production_order_item i ON i.id = u.production_order_item_id
        JOIN product_inventory_kardex k ON k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
             AND k.movement_type = 'PRODUCTION_ENTRY' AND k.location_id = v_pt
        WHERE UPPER(po.seller_name) LIKE '%LUIS FELIPE%' AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
          AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex m
                          WHERE m.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND m.reference_id = u.id
                            AND m.movement_type IN ('RESERVE_TRANSFER_IN', 'RESERVE_RELEASE'))
        ORDER BY k.id
    LOOP
        v_size := r.size_key;
        IF pg_temp.inv_available(r.product_id, r.color_id, v_pt, v_size) < 1 THEN
            INSERT INTO inv_fix_20261026_pendientes
            VALUES ('PT sin esta pieza: no se movió', r.unit_id, r.po_code, r.entry_kardex_id,
                    r.product_id, r.color_id, v_size, 1);
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_pt, -1, v_size, 'RESERVE_TRANSFER_OUT',
                                 'PRODUCTION_ORDER_WH_UNIT', r.unit_id, r.po_code || '-WH-UNIT-' || r.unit_id,
                                 'FIX-20261026 pieza vendida (Luis Felipe) pasa a reserva: ' || COALESCE(r.unit_label, ''),
                                 r.unit_id);
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_res, 1, v_size, 'RESERVE_TRANSFER_IN',
                                 'PRODUCTION_ORDER_WH_UNIT', r.unit_id, r.po_code || '-WH-UNIT-' || r.unit_id,
                                 'FIX-20261026 pieza vendida (Luis Felipe) pasa a reserva: ' || COALESCE(r.unit_label, ''),
                                 r.unit_id);
        v_moved := v_moved + 1;
        IF r.closed THEN
            PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_res, -1, v_size, 'RESERVE_RELEASE',
                                     'PRODUCTION_ORDER_WH_UNIT', r.unit_id, r.po_code || '-WH-UNIT-' || r.unit_id,
                                     'FIX-20261026 entrega a vendedor (recepción cerrada): ' || COALESCE(r.unit_label, ''),
                                     r.unit_id);
            UPDATE production_order_warehouse_unit
               SET shipped_at = COALESCE(shipped_at, now()), shipment_ref_type = COALESCE(shipment_ref_type, 'VENDOR_DELIVERY')
             WHERE id = r.unit_id;
            v_released := v_released + 1;
        END IF;
    END LOOP;
    RAISE NOTICE 'A) reserva LF: % pasadas a reserva, % entregadas (cerradas), % pendientes', v_moved, v_released, v_skipped;
END $$;

-- ----------------------------------------------------------------------------
-- B) Salidas de OP a pedido que salieron de Devoluciones: devolver a Devoluciones y sacar de PT
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_pt bigint := (SELECT id FROM locations WHERE code = 'BODEGA_PT');
    v_dev bigint := (SELECT id FROM locations WHERE code = 'DEVOLUCION');
    r record;
    v_fixed int := 0;
    v_skipped int := 0;
BEGIN
    FOR r IN
        SELECT k.id AS kardex_id, k.product_id, k.color_id, NULLIF(k.size_label, '') AS size_label,
               -k.quantity AS qty, k.reference_type, k.reference_id, k.reference_number, k.movement_type
        FROM product_inventory_kardex k
        LEFT JOIN product_shipment s ON k.reference_type = 'SHIPMENT' AND s.id = k.reference_id
        LEFT JOIN production_order po ON po.id = s.production_order_id
        WHERE k.location_id = v_dev AND k.quantity < 0 AND k.movement_date >= '2026-10-15'
          AND (k.movement_type = 'ONLINE_SALE_DISPATCH'
               OR (po.order_type IN ('CLIENTE_KIOSKO', 'INTERNA') OR po.order_type LIKE 'CINCHOS%')
                  AND UPPER(COALESCE(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%')
          AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex f
                          WHERE f.reference_type = 'INV_FIX_20261026' AND f.reference_id = k.id
                            AND f.movement_type = 'ORIGIN_FIX_IN')
        ORDER BY k.id
    LOOP
        IF pg_temp.inv_available(r.product_id, r.color_id, v_pt, r.size_label) < r.qty THEN
            INSERT INTO inv_fix_20261026_pendientes
            VALUES ('PT sin la unidad: no se corrigió el origen', NULL, r.reference_number, r.kardex_id,
                    r.product_id, r.color_id, r.size_label, r.qty);
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_pt, -r.qty, r.size_label, 'ORIGIN_FIX_OUT',
                                 'INV_FIX_20261026', r.kardex_id, r.reference_number,
                                 'FIX-20261026 la salida ' || r.reference_number || ' debía salir de PT', NULL);
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_dev, r.qty, r.size_label, 'ORIGIN_FIX_IN',
                                 'INV_FIX_20261026', r.kardex_id, r.reference_number,
                                 'FIX-20261026 la salida ' || r.reference_number || ' debía salir de PT', NULL);
        v_fixed := v_fixed + 1;
    END LOOP;
    RAISE NOTICE 'B) origen de salidas: % corregidas, % pendientes', v_fixed, v_skipped;
END $$;


-- =============================================================================
-- PASO 3. VERIFICACIÓN (revise y luego COMMIT; o ROLLBACK;)
-- =============================================================================
-- 3a. Antes (respaldo) contra después, por ubicación
SELECT l.code,
       (SELECT COALESCE(SUM(b.quantity), 0) FROM inv_fix_20261026_bk_stock b WHERE b.location_id = l.id) AS unidades_antes,
       (SELECT COALESCE(SUM(p.quantity), 0) FROM product_inventory_location p WHERE p.location_id = l.id) AS unidades_despues
FROM locations l WHERE l.code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF') ORDER BY l.code;

-- 3b. Stock = suma de kardex en las tres ubicaciones (debe dar 0 filas)
SELECT l.code, p.code AS producto, pil.color_id, pil.quantity AS stock, COALESCE(k.s, 0) AS kardex
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id AND l.code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF')
JOIN product p ON p.id = pil.product_id
LEFT JOIN (SELECT product_id, location_id, COALESCE(color_id, 0) AS c, SUM(quantity) AS s
           FROM product_inventory_kardex GROUP BY 1, 2, 3) k
       ON k.product_id = pil.product_id AND k.location_id = pil.location_id AND k.c = COALESCE(pil.color_id, 0)
WHERE ABS(pil.quantity - COALESCE(k.s, 0)) > 0.0001;

-- 3c. Cadena quantity_before/after sin quiebres en lo movido (debe dar 0 filas)
WITH k AS (
    SELECT id, product_id, location_id, COALESCE(color_id, 0) AS c, quantity_before,
           LAG(quantity_after) OVER (PARTITION BY product_id, location_id, COALESCE(color_id, 0) ORDER BY id) AS prev_after
    FROM product_inventory_kardex
    WHERE location_id IN (SELECT id FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF'))
)
SELECT * FROM k WHERE prev_after IS NOT NULL AND quantity_before <> prev_after;

-- 3d. Reserva = piezas recibidas de LF cuya recepción sigue abierta (las cerradas ya salieron)
SELECT (SELECT COALESCE(SUM(quantity), 0) FROM product_inventory_location
         WHERE location_id = (SELECT id FROM locations WHERE code = 'RESERVA_LF')) AS reserva,
       (SELECT COUNT(*) FROM production_order po
          JOIN production_order_warehouse_unit u ON u.production_order_id = po.id AND u.receipt_status = 'RECEIVED'
         WHERE UPPER(po.seller_name) LIKE '%LUIS FELIPE%' AND (po.code LIKE 'OPV-%' OR po.order_type LIKE 'CINCHOS%')
           AND po.warehouse_receipt_closed_at IS NULL
           AND EXISTS (SELECT 1 FROM product_inventory_kardex m WHERE m.reference_type = 'PRODUCTION_ORDER_WH_UNIT'
                       AND m.reference_id = u.id AND m.movement_type = 'RESERVE_TRANSFER_IN')) AS piezas_abiertas_en_reserva;

-- 3e. Lo que NO se pudo mover y requiere revisión manual
SELECT * FROM inv_fix_20261026_pendientes ORDER BY motivo, orden;

-- Si todo está bien:  COMMIT;
-- Si algo no cuadra:  ROLLBACK;


-- =============================================================================
-- DESHACER (solo si ya hizo COMMIT y hay que volver atrás)
-- =============================================================================
--   BEGIN;
--   DELETE FROM product_inventory_kardex
--    WHERE movement_type IN ('RESERVE_TRANSFER_OUT', 'RESERVE_TRANSFER_IN', 'RESERVE_RELEASE', 'ORIGIN_FIX_OUT', 'ORIGIN_FIX_IN')
--      AND description LIKE 'FIX-20261026%';
--   UPDATE production_order_warehouse_unit SET shipped_at = NULL, shipment_ref_type = NULL
--    WHERE shipment_ref_type = 'VENDOR_DELIVERY';
--   DELETE FROM product_inventory_location
--    WHERE location_id IN (SELECT id FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION', 'RESERVA_LF'));
--   INSERT INTO product_inventory_location SELECT * FROM inv_fix_20261026_bk_stock;
--   COMMIT;
