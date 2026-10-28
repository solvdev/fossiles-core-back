-- =============================================================================
-- DEVOLUCIONES Y ANULACIONES → BODEGA DEVOLUCIONES: auditoría + corrección — PostgreSQL
-- =============================================================================
-- Acompaña al cambio de código "toda devolución/anulación carga a Devoluciones" (rama
-- hotfix/devoluciones-inventario). Revisa desde el corte del 15-oct (la limpieza dejó las bodegas en 0
-- y su kardex en product_inventory_kardex_historico; lo anterior no cuenta).
--
--   PASO 1  Auditoría (solo lectura). Cada consulta debe salir VACÍA; si algo sale, es un descuadre.
--   PASO 2  Corrección (BEGIN sin COMMIT): piezas de OPL de ventas ya ANULADAS/DEVUELTAS que quedaron en
--           Bodega PT → Devoluciones. Usa los mismos movimientos que el back (TRANSFER_OUT en PT +
--           RETURN en Devoluciones, referencia ONLINE_SALE_WH_RETURN / venta / pieza), así que el back
--           reconoce lo ya movido y no lo vuelve a mover. Idempotente.
--   PASO 3  Verificación; luego COMMIT; (o ROLLBACK;).
-- Correr DESPUÉS de desplegar el back.
-- =============================================================================


-- =============================================================================
-- PASO 1. AUDITORÍA (solo lectura)
-- =============================================================================

-- 1a. Devoluciones y cambios de venta en línea sin su entrada a Devoluciones (o DEV_NO_APTA)
SELECT r.id AS devolucion, r.created_at::timestamp(0) AS fecha, s.shipment_number, s.status,
       l.product_code, l.color_name, l.size, l.quantity AS devuelto,
       COALESCE((SELECT SUM(k.quantity) FROM product_inventory_kardex k
                 WHERE k.movement_type = 'RETURN'
                   AND ((k.reference_type = 'ONLINE_SALE_RETURN' AND k.reference_id = r.online_sale_id
                         AND r.exchange_shipment_number IS NULL)
                     OR (k.reference_type = 'ONLINE_SALE_EXCHANGE_RETURN' AND k.reference_id = r.id))
                   AND k.product_id = l.product_id AND k.color_id IS NOT DISTINCT FROM l.color_id
                   AND COALESCE(k.size_label, '') = COALESCE(l.size, '')), 0) AS acreditado
FROM online_sale_return r
JOIN online_sale s ON s.id = r.online_sale_id
JOIN online_sale_return_line l ON l.return_id = r.id
WHERE r.created_at >= '2026-10-15'
  AND l.quantity <> COALESCE((SELECT SUM(k.quantity) FROM product_inventory_kardex k
                 WHERE k.movement_type = 'RETURN'
                   AND ((k.reference_type = 'ONLINE_SALE_RETURN' AND k.reference_id = r.online_sale_id
                         AND r.exchange_shipment_number IS NULL)
                     OR (k.reference_type = 'ONLINE_SALE_EXCHANGE_RETURN' AND k.reference_id = r.id))
                   AND k.product_id = l.product_id AND k.color_id IS NOT DISTINCT FROM l.color_id
                   AND COALESCE(k.size_label, '') = COALESCE(l.size, '')), 0)
ORDER BY r.id;

-- 1b. Ventas devueltas que además tuvieron un CAMBIO: lo cambiado no debe acreditarse dos veces
SELECT s.id, s.shipment_number, l.product_code, l.color_name, l.size,
       SUM(l.quantity) AS regresado, MAX(COALESCE(i.quantity, s.quantity, 1)) AS vendido
FROM online_sale s
JOIN online_sale_return r ON r.online_sale_id = s.id
JOIN online_sale_return_line l ON l.return_id = r.id
LEFT JOIN online_sale_item i ON i.online_sale_id = s.id AND i.product_id = l.product_id
     AND i.color_id IS NOT DISTINCT FROM l.color_id AND COALESCE(i.size, '') = COALESCE(l.size, '')
WHERE s.status = 'DEVOLUCION' AND r.created_at >= '2026-10-15'
GROUP BY s.id, s.shipment_number, l.product_code, l.color_name, l.size
HAVING SUM(l.quantity) > MAX(COALESCE(i.quantity, s.quantity, 1));

-- 1c. Ventas ANULADAS con salida de inventario que nunca regresó (PREPARE/DISPATCH con neto negativo)
SELECT s.id, s.shipment_number, s.status, k.reference_type, SUM(k.quantity) AS neto
FROM online_sale s
JOIN product_inventory_kardex k ON k.reference_id = s.id
     AND k.reference_type IN ('ONLINE_SALE_PREPARE', 'ONLINE_SALE_DISPATCH')
WHERE s.status IN ('ANULADA', 'CANCELADO') AND k.movement_date >= '2026-10-15'
GROUP BY s.id, s.shipment_number, s.status, k.reference_type
HAVING SUM(k.quantity) < 0;

-- 1d. Piezas de OPL de ventas ya ANULADAS/DEVUELTAS que siguen contadas en Bodega PT (lo corrige el PASO 2)
SELECT s.id AS venta, s.shipment_number, s.status, po.code AS orden, u.id AS pieza, u.unit_label,
       u.received_at::timestamp(0) AS recibida
FROM production_order_warehouse_unit u
JOIN production_order_item i ON i.id = u.production_order_item_id
JOIN production_order po ON po.id = u.production_order_id
JOIN online_sale s ON s.id = i.online_sale_id
JOIN product_inventory_kardex e ON e.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND e.reference_id = u.id
     AND e.movement_type = 'PRODUCTION_ENTRY' AND e.location_id = (SELECT id FROM locations WHERE code = 'BODEGA_PT')
WHERE s.status IN ('ANULADA', 'CANCELADO', 'DEVOLUCION')
  AND u.receipt_status = 'RECEIVED' AND u.shipped_at IS NULL
  AND e.movement_date >= '2026-10-15'
  AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex t
                  WHERE t.reference_type = 'ONLINE_SALE_WH_RETURN' AND t.reference_id = s.id
                    AND t.movement_type = 'TRANSFER_OUT' AND t.reference_line_id = u.id)
ORDER BY u.received_at;

-- 1e. Reintegros de kiosko a bodega (DEVOLUCION_DEPOSITO) sin entrada a Devoluciones / DEV_NO_APTA
SELECT m.id, m.created_at::timestamp(0), l.name AS kiosko, p.code, m.quantity
FROM kiosco_movement m
JOIN kiosco_stock ks ON ks.id = m.kiosco_stock_id
JOIN locations l ON l.id = ks.location_id
JOIN product p ON p.id = ks.product_id
WHERE m.movement_type = 'DEVOLUCION_DEPOSITO' AND m.affects_stock AND m.created_at >= '2026-10-15'
  AND COALESCE((SELECT SUM(k.quantity) FROM product_inventory_kardex k
                WHERE k.reference_type = 'KIOSK_DEPOSIT_RETURN' AND k.reference_id = m.id), 0) <> m.quantity
ORDER BY m.id;

-- 1f. Ventas eliminadas que dejaron salida de inventario (la venta ya no existe)
SELECT k.reference_type, k.reference_id, k.reference_number, SUM(k.quantity) AS neto
FROM product_inventory_kardex k
WHERE k.reference_type IN ('ONLINE_SALE_PREPARE', 'ONLINE_SALE_DISPATCH')
  AND NOT EXISTS (SELECT 1 FROM online_sale s WHERE s.id = k.reference_id)
GROUP BY k.reference_type, k.reference_id, k.reference_number
HAVING SUM(k.quantity) <> 0;

-- 1g. Devoluciones: stock = suma de su kardex
WITH dev AS (SELECT id FROM locations WHERE code = 'DEVOLUCION'),
     kx AS (SELECT product_id, color_id, SUM(quantity) q FROM product_inventory_kardex
            WHERE location_id = (SELECT id FROM dev) GROUP BY 1, 2),
     st AS (SELECT product_id, color_id, SUM(quantity) q FROM product_inventory_location
            WHERE location_id = (SELECT id FROM dev) GROUP BY 1, 2)
SELECT p.code, COALESCE(kx.color_id, st.color_id) AS color_id, kx.q AS kardex, st.q AS stock
FROM kx FULL JOIN st ON st.product_id = kx.product_id AND st.color_id IS NOT DISTINCT FROM kx.color_id
JOIN product p ON p.id = COALESCE(kx.product_id, st.product_id)
WHERE COALESCE(kx.q, 0) <> COALESCE(st.q, 0);


-- =============================================================================
-- PASO 2. CORRECCIÓN (una sola transacción): piezas de 1d → Devoluciones
-- =============================================================================
BEGIN;

DROP TABLE IF EXISTS dev_fix_20261028_bk_stock;
CREATE TABLE dev_fix_20261028_bk_stock AS
SELECT pil.* FROM product_inventory_location pil
WHERE pil.location_id IN (SELECT id FROM locations WHERE code IN ('BODEGA_PT', 'DEVOLUCION'));

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

DO $$
DECLARE
    v_pt bigint := (SELECT id FROM locations WHERE code = 'BODEGA_PT');
    v_dev bigint := (SELECT id FROM locations WHERE code = 'DEVOLUCION');
    r record;
    v_moved int := 0;
    v_skipped int := 0;
BEGIN
    FOR r IN
        SELECT s.id AS sale_id, COALESCE(s.sale_number, s.id::text) AS sale_ref, s.status,
               u.id AS unit_id, i.product_id, i.color_id, NULLIF(u.size_key, '') AS size_key
        FROM production_order_warehouse_unit u
        JOIN production_order_item i ON i.id = u.production_order_item_id
        JOIN online_sale s ON s.id = i.online_sale_id
        JOIN product_inventory_kardex e ON e.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND e.reference_id = u.id
             AND e.movement_type = 'PRODUCTION_ENTRY' AND e.location_id = v_pt
        WHERE s.status IN ('ANULADA', 'CANCELADO', 'DEVOLUCION')
          AND u.receipt_status = 'RECEIVED' AND u.shipped_at IS NULL
          AND e.movement_date >= '2026-10-15'
          AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex t
                          WHERE t.reference_type = 'ONLINE_SALE_WH_RETURN' AND t.reference_id = s.id
                            AND t.movement_type = 'TRANSFER_OUT' AND t.reference_line_id = u.id)
        ORDER BY u.id
    LOOP
        IF (SELECT COALESCE(SUM(quantity), 0) FROM product_inventory_location
            WHERE product_id = r.product_id AND location_id = v_pt
              AND color_id IS NOT DISTINCT FROM r.color_id) < 1 THEN
            RAISE NOTICE 'Pieza % (venta %): PT ya no la tiene, no se movió', r.unit_id, r.sale_id;
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_pt, -1, r.size_key, 'TRANSFER_OUT',
                                 'ONLINE_SALE_WH_RETURN', r.sale_id, r.sale_ref,
                                 'FIX-20261028 pieza de venta ' || r.status || ' #' || r.sale_ref || ' → bodega devoluciones',
                                 r.unit_id);
        PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_dev, 1, r.size_key, 'RETURN',
                                 'ONLINE_SALE_WH_RETURN', r.sale_id, r.sale_ref,
                                 'FIX-20261028 pieza de venta ' || r.status || ' #' || r.sale_ref || ' → bodega devoluciones',
                                 r.unit_id);
        v_moved := v_moved + 1;
    END LOOP;
    RAISE NOTICE 'Piezas de ventas anuladas/devueltas: % pasadas a Devoluciones, % sin mover', v_moved, v_skipped;
END $$;


-- =============================================================================
-- PASO 3. VERIFICACIÓN (revise y luego COMMIT; o ROLLBACK;)
-- =============================================================================
SELECT l.code,
       (SELECT COALESCE(SUM(quantity), 0) FROM dev_fix_20261028_bk_stock b WHERE b.location_id = l.id) AS antes,
       (SELECT COALESCE(SUM(quantity), 0) FROM product_inventory_location p WHERE p.location_id = l.id) AS despues
FROM locations l WHERE l.code IN ('BODEGA_PT', 'DEVOLUCION') ORDER BY l.code;

SELECT k.id, k.location_id, k.movement_type, k.quantity, k.reference_number, k.description
FROM product_inventory_kardex k WHERE k.description LIKE 'FIX-20261028%' ORDER BY k.id;

-- COMMIT;
-- ROLLBACK;


-- =============================================================================
-- DESHACER (después del COMMIT)
-- =============================================================================
-- BEGIN;
-- UPDATE product_inventory_location p SET quantity = b.quantity, sizes_data = b.sizes_data, updated_at = now()
--   FROM dev_fix_20261028_bk_stock b WHERE b.id = p.id;
-- DELETE FROM product_inventory_kardex WHERE description LIKE 'FIX-20261028%';
-- COMMIT;
