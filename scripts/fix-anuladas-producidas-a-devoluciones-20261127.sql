-- =============================================================================
-- VENTAS EN LÍNEA ANULADAS CON PIEZAS YA PRODUCIDAS → BODEGA DEVOLUCIONES — PostgreSQL
-- =============================================================================
-- Acompaña al cambio "anular venta: lo producido entra a Devoluciones" (rama
-- hotfix/anular-venta-producida-a-devoluciones). Caso: la OPL terminó en producción (lista para despacho)
-- pero Bodega nunca recibió las piezas; al anular no había nada en PT que mover y no se cargó nada.
-- Lo que no se produjo no se toca.
--
-- Hace lo mismo que el back al recibir la pieza de una venta anulada: crea la pieza de bodega si no
-- existe, la marca RECEIVED y la ingresa a Devoluciones con PRODUCTION_ENTRY / PRODUCTION_ORDER_WH_UNIT
-- (referencia = pieza), así el back la reconoce como recibida y no la vuelve a ingresar. Idempotente.
--
-- Al 27-nov (copia de prod 07:44): #66 (OPL-1467: BD-11 y B-18 tostado) y #185 (OPL-1379: BD-28 tostado).
--
--   PASO 1  Auditoría (solo lectura).
--   PASO 2  Corrección (BEGIN sin COMMIT).
--   PASO 3  Verificación; luego COMMIT; (o ROLLBACK;).
-- Se puede correr antes o después de desplegar el back.
-- =============================================================================


-- =============================================================================
-- PASO 1. AUDITORÍA: líneas producidas de ventas anuladas que no entraron a ninguna bodega
-- =============================================================================
WITH done AS (
    SELECT ti.production_order_item_id AS poi_id, SUM(GREATEST(COALESCE(ti.quantity, 0), 0)) AS q
    FROM task_item ti JOIN task t ON t.id = ti.task_id
    WHERE t.status IN ('COMPLETED', 'AWAITING_WAREHOUSE')
      AND COALESCE(t.observations, '') NOT LIKE 'REPROCESO%'
    GROUP BY 1),
inn AS (
    SELECT production_order_item_id AS poi_id,
           COUNT(*) FILTER (WHERE receipt_status = 'RECEIVED' OR shipped_at IS NOT NULL) AS q,
           COUNT(*) AS piezas
    FROM production_order_warehouse_unit GROUP BY 1)
SELECT s.id AS venta_id, s.sale_number, s.status, s.updated_at::timestamp(0) AS anulada,
       po.code AS op, i.id AS linea, p.code AS producto, c.name AS color, i.quantity AS pedido,
       LEAST(d.q, i.quantity) AS producido, COALESCE(inn.q, 0) AS ya_en_bodega,
       COALESCE(inn.piezas, 0) AS piezas_creadas, i.sizes_data
FROM online_sale s
JOIN production_order_item i ON i.online_sale_id = s.id
JOIN production_order po ON po.id = i.production_order_id
JOIN product p ON p.id = i.product_id
LEFT JOIN colors c ON c.id = i.color_id
JOIN done d ON d.poi_id = i.id
LEFT JOIN inn ON inn.poi_id = i.id
WHERE s.status IN ('ANULADA', 'CANCELADO')
  AND LEAST(d.q, i.quantity) > COALESCE(inn.q, 0)
ORDER BY s.updated_at DESC, i.id;


-- =============================================================================
-- PASO 2. CORRECCIÓN (una sola transacción)
-- =============================================================================
BEGIN;

DROP TABLE IF EXISTS anul_prod_fix_20261127_bk_stock;
CREATE TABLE anul_prod_fix_20261127_bk_stock AS
SELECT pil.* FROM product_inventory_location pil
WHERE pil.location_id = (SELECT id FROM locations WHERE code = 'DEVOLUCION');

-- Suma una cantidad en una ubicación (con talla opcional) y deja la fila de kardex encadenada.
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
    v_sizes := CASE WHEN v_row.sizes_data IS NULL OR v_row.sizes_data = '' THEN NULL ELSE v_row.sizes_data::jsonb END;
    IF p_size IS NOT NULL AND p_size <> '' THEN
        v_cur := COALESCE((v_sizes ->> p_size)::numeric, 0);
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
    v_dev bigint := (SELECT id FROM locations WHERE code = 'DEVOLUCION');
    r record;
    u record;
    v_size text;
    v_to_receive int;
    v_done int;
    v_total int := 0;
    v_skipped int := 0;
BEGIN
    IF v_dev IS NULL THEN
        RAISE EXCEPTION 'No existe la ubicación DEVOLUCION';
    END IF;
    FOR r IN
        WITH done AS (
            SELECT ti.production_order_item_id AS poi_id, SUM(GREATEST(COALESCE(ti.quantity, 0), 0)) AS q
            FROM task_item ti JOIN task t ON t.id = ti.task_id
            WHERE t.status IN ('COMPLETED', 'AWAITING_WAREHOUSE')
              AND COALESCE(t.observations, '') NOT LIKE 'REPROCESO%'
            GROUP BY 1)
        SELECT s.id AS sale_id, COALESCE(s.sale_number, s.id::text) AS sale_ref,
               po.id AS po_id, po.code AS po_code, i.id AS poi_id, i.product_id, i.color_id,
               i.quantity, i.sizes_data, i.online_sale_item_id,
               LEAST(d.q, i.quantity)::int AS produced, p.code AS product_code, c.name AS color_name
        FROM online_sale s
        JOIN production_order_item i ON i.online_sale_id = s.id
        JOIN production_order po ON po.id = i.production_order_id
        JOIN product p ON p.id = i.product_id
        LEFT JOIN colors c ON c.id = i.color_id
        JOIN done d ON d.poi_id = i.id
        WHERE s.status IN ('ANULADA', 'CANCELADO')
        ORDER BY i.id
    LOOP
        -- Piezas de bodega de la línea (si Bodega nunca abrió la orden, no existen).
        IF NOT EXISTS (SELECT 1 FROM production_order_warehouse_unit WHERE production_order_item_id = r.poi_id) THEN
            IF r.sizes_data IS NOT NULL AND r.sizes_data NOT IN ('', '{}', 'null') THEN
                RAISE NOTICE 'Línea % (% venta %): tiene tallas en sizes_data, revisar a mano', r.poi_id, r.product_code, r.sale_ref;
                v_skipped := v_skipped + 1;
                CONTINUE;
            END IF;
            v_size := COALESCE((SELECT NULLIF(TRIM(si.size), '') FROM online_sale_item si WHERE si.id = r.online_sale_item_id), '');
            INSERT INTO production_order_warehouse_unit
                (production_order_id, production_order_item_id, unit_label, color_id, size_key, unit_seq,
                 receipt_status, created_at)
            SELECT r.po_id, r.poi_id,
                   '#' || g || COALESCE(' · ' || r.color_name, '')
                       || CASE WHEN v_size <> '' THEN ' · Talla ' || v_size ELSE '' END
                       || ' · ' || r.product_code,
                   r.color_id, v_size, g, 'PENDING', now()
            FROM generate_series(1, GREATEST(COALESCE(r.quantity, 0), 0)) g;
        END IF;

        v_to_receive := r.produced - (SELECT COUNT(*) FROM production_order_warehouse_unit
                                      WHERE production_order_item_id = r.poi_id
                                        AND (receipt_status = 'RECEIVED' OR shipped_at IS NOT NULL));
        v_done := 0;
        FOR u IN
            SELECT * FROM production_order_warehouse_unit
            WHERE production_order_item_id = r.poi_id AND receipt_status = 'PENDING' AND shipped_at IS NULL
            ORDER BY size_key, unit_seq
        LOOP
            EXIT WHEN v_done >= v_to_receive;
            -- Ya ingresada por el back (misma referencia): solo marcarla.
            IF NOT EXISTS (SELECT 1 FROM product_inventory_kardex k
                           WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
                             AND k.movement_type = 'PRODUCTION_ENTRY') THEN
                PERFORM pg_temp.inv_move(r.product_id, r.color_id, v_dev, 1, NULLIF(u.size_key, ''),
                                         'PRODUCTION_ENTRY', 'PRODUCTION_ORDER_WH_UNIT', u.id,
                                         r.po_code || '-WH-UNIT-' || u.id,
                                         'FIX-20261127 pieza producida de venta anulada #' || r.sale_ref
                                             || ' → bodega devoluciones',
                                         NULL);
            END IF;
            UPDATE production_order_warehouse_unit
               SET receipt_status = 'RECEIVED', received_at = now(), rejection_reason = NULL
             WHERE id = u.id;
            v_done := v_done + 1;
        END LOOP;

        IF v_done > 0 THEN
            UPDATE production_order_item
               SET warehouse_received_qty = (SELECT COUNT(*) FROM production_order_warehouse_unit
                                             WHERE production_order_item_id = r.poi_id AND receipt_status = 'RECEIVED')
             WHERE id = r.poi_id;
            RAISE NOTICE 'Venta #% %: % % pieza(s) a Devoluciones', r.sale_ref, r.po_code, r.product_code, v_done;
            v_total := v_total + v_done;
        END IF;
    END LOOP;
    RAISE NOTICE 'Total: % pieza(s) ingresadas a Devoluciones, % línea(s) para revisar a mano', v_total, v_skipped;
END $$;


-- =============================================================================
-- PASO 3. VERIFICACIÓN (revise y luego COMMIT; o ROLLBACK;)
-- =============================================================================
SELECT (SELECT COALESCE(SUM(quantity), 0) FROM anul_prod_fix_20261127_bk_stock) AS devoluciones_antes,
       (SELECT COALESCE(SUM(quantity), 0) FROM product_inventory_location
        WHERE location_id = (SELECT id FROM locations WHERE code = 'DEVOLUCION')) AS devoluciones_despues;

SELECT k.id, p.code, k.color_id, k.movement_type, k.quantity, k.quantity_before, k.quantity_after,
       k.reference_number, k.description
FROM product_inventory_kardex k JOIN product p ON p.id = k.product_id
WHERE k.description LIKE 'FIX-20261127%' ORDER BY k.id;

-- COMMIT;
-- ROLLBACK;


-- =============================================================================
-- DESHACER (después del COMMIT)
-- =============================================================================
-- BEGIN;
-- UPDATE product_inventory_location p SET quantity = b.quantity, sizes_data = b.sizes_data, updated_at = now()
--   FROM anul_prod_fix_20261127_bk_stock b WHERE b.id = p.id;
-- DELETE FROM product_inventory_location p
--  WHERE p.location_id = (SELECT id FROM locations WHERE code = 'DEVOLUCION')
--    AND NOT EXISTS (SELECT 1 FROM anul_prod_fix_20261127_bk_stock b WHERE b.id = p.id);
-- UPDATE production_order_warehouse_unit u SET receipt_status = 'PENDING', received_at = NULL
--   FROM product_inventory_kardex k
--  WHERE k.description LIKE 'FIX-20261127%' AND k.reference_id = u.id;
-- DELETE FROM product_inventory_kardex WHERE description LIKE 'FIX-20261127%';
-- COMMIT;
