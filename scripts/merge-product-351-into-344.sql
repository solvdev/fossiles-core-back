-- =============================================================================
-- Fusionar producto 351 (BD-500 BOLSO ANA) → 344 (BD-25 BOLSO DAMA ANA)
-- y dejar 351 sin usos (luego se desactiva / borra).
--
-- IMPORTANTE
-- 1) Corre primero scripts/audit-product-id-usage-counts.sql con 351 y 344.
-- 2) Ejecuta TODO este archivo en UNA sola transacción (BEGIN…COMMIT).
-- 3) Requiere la función prevent_kiosco_movement_mutation con flag admin
--    (scripts/migration-kiosco-movement-admin-delete.sql).
-- 4) Haz backup / snapshot antes.
-- =============================================================================

BEGIN;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

DO $$
DECLARE
  v_from BIGINT := 351;
  v_to   BIGINT := 344;
  v_from_code TEXT;
  v_to_code   TEXT;
  v_to_name   TEXT;
  v_left BIGINT;
BEGIN
  SELECT code INTO v_from_code FROM product WHERE id = v_from;
  SELECT code, name INTO v_to_code, v_to_name FROM product WHERE id = v_to;

  IF v_from_code IS NULL THEN
    RAISE EXCEPTION 'Producto origen % no existe', v_from;
  END IF;
  IF v_to_code IS NULL THEN
    RAISE EXCEPTION 'Producto destino % no existe', v_to;
  END IF;

  RAISE NOTICE 'Merge % (%) → % (%)', v_from, v_from_code, v_to, v_to_code;

  -- -------------------------------------------------------------------------
  -- 1) kiosco_stock: mover movimientos + sumar stock; borrar filas del 351
  -- -------------------------------------------------------------------------
  -- 1a) Conflicto: existe fila 351 y 344 misma clave → consolidar en 344
  WITH pairs AS (
    SELECT
      a.id AS from_id,
      b.id AS to_id,
      COALESCE(a.current_stock, 0) AS from_stock,
      COALESCE(b.current_stock, 0) AS to_stock,
      GREATEST(COALESCE(a.minimum_stock, 0), COALESCE(b.minimum_stock, 0)) AS min_stock,
      CASE
        WHEN b.sizes_data IS NULL OR btrim(b.sizes_data) IN ('', '{}', 'null')
          THEN a.sizes_data
        ELSE b.sizes_data
      END AS keep_sizes
    FROM kiosco_stock a
    JOIN kiosco_stock b
      ON b.product_id = v_to
     AND b.location_id = a.location_id
     AND b.color_id IS NOT DISTINCT FROM a.color_id
     AND b.hardware_condition = a.hardware_condition
    WHERE a.product_id = v_from
  ),
  mov AS (
    UPDATE kiosco_movement m
    SET kiosco_stock_id = p.to_id
    FROM pairs p
    WHERE m.kiosco_stock_id = p.from_id
    RETURNING m.id
  ),
  upd AS (
    UPDATE kiosco_stock ks
    SET current_stock = p.to_stock + p.from_stock,
        minimum_stock = p.min_stock,
        sizes_data = p.keep_sizes,
        updated_at = NOW(),
        last_updated_at = NOW()
    FROM pairs p
    WHERE ks.id = p.to_id
    RETURNING ks.id
  )
  DELETE FROM kiosco_stock ks
  USING pairs p
  WHERE ks.id = p.from_id;

  -- 1b) Sin conflicto: remapar product_id
  UPDATE kiosco_stock
  SET product_id = v_to,
      updated_at = NOW(),
      last_updated_at = NOW()
  WHERE product_id = v_from;

  -- -------------------------------------------------------------------------
  -- 2) product_inventory_location
  -- -------------------------------------------------------------------------
  WITH pairs AS (
    SELECT a.id AS from_id, b.id AS to_id,
           COALESCE(a.quantity, 0) AS from_qty,
           COALESCE(b.quantity, 0) AS to_qty,
           CASE
             WHEN b.sizes_data IS NULL OR btrim(b.sizes_data) IN ('', '{}', 'null')
               THEN a.sizes_data
             ELSE b.sizes_data
           END AS keep_sizes
    FROM product_inventory_location a
    JOIN product_inventory_location b
      ON b.product_id = v_to
     AND b.location_id = a.location_id
     AND b.color_id IS NOT DISTINCT FROM a.color_id
    WHERE a.product_id = v_from
  ),
  upd AS (
    UPDATE product_inventory_location pil
    SET quantity = p.to_qty + p.from_qty,
        sizes_data = p.keep_sizes,
        product_name = COALESCE(v_to_name, pil.product_name),
        updated_at = NOW()
    FROM pairs p
    WHERE pil.id = p.to_id
    RETURNING pil.id
  )
  DELETE FROM product_inventory_location pil
  USING pairs p
  WHERE pil.id = p.from_id;

  UPDATE product_inventory_location
  SET product_id = v_to,
      product_name = COALESCE(v_to_name, product_name),
      updated_at = NOW()
  WHERE product_id = v_from;

  -- -------------------------------------------------------------------------
  -- 3) product_shipment_detail
  -- -------------------------------------------------------------------------
  WITH pairs AS (
    SELECT a.id AS from_id, b.id AS to_id,
           COALESCE(a.quantity, 0) AS from_qty,
           COALESCE(b.quantity, 0) AS to_qty
    FROM product_shipment_detail a
    JOIN product_shipment_detail b
      ON b.product_id = v_to
     AND b.shipment_id = a.shipment_id
     AND b.color_id IS NOT DISTINCT FROM a.color_id
     AND COALESCE(b.size_label, '') = COALESCE(a.size_label, '')
     AND COALESCE(b.hardware_condition, '') = COALESCE(a.hardware_condition, '')
    WHERE a.product_id = v_from
  ),
  upd AS (
    UPDATE product_shipment_detail d
    SET quantity = p.to_qty + p.from_qty
    FROM pairs p
    WHERE d.id = p.to_id
    RETURNING d.id
  )
  DELETE FROM product_shipment_detail d
  USING pairs p
  WHERE d.id = p.from_id;

  UPDATE product_shipment_detail
  SET product_id = v_to
  WHERE product_id = v_from;

  -- -------------------------------------------------------------------------
  -- 4) envio_detalle
  -- -------------------------------------------------------------------------
  IF to_regclass('public.envio_detalle') IS NOT NULL THEN
    EXECUTE format($q$
      WITH pairs AS (
        SELECT a.id AS from_id, b.id AS to_id,
               COALESCE(a.cantidad, 0) AS from_qty,
               COALESCE(b.cantidad, 0) AS to_qty
        FROM envio_detalle a
        JOIN envio_detalle b
          ON b.product_id = %s
         AND b.envio_id = a.envio_id
        WHERE a.product_id = %s
      ),
      upd AS (
        UPDATE envio_detalle d
        SET cantidad = p.to_qty + p.from_qty
        FROM pairs p
        WHERE d.id = p.to_id
        RETURNING d.id
      )
      DELETE FROM envio_detalle d
      USING pairs p
      WHERE d.id = p.from_id
    $q$, v_to, v_from);

    EXECUTE format(
      'UPDATE envio_detalle SET product_id = %s WHERE product_id = %s',
      v_to, v_from
    );
  END IF;

  -- -------------------------------------------------------------------------
  -- 5) product_variant_leather (si choca, se queda el del 344)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.product_variant_leather') IS NOT NULL THEN
    DELETE FROM product_variant_leather a
    USING product_variant_leather b
    WHERE a.product_id = v_from
      AND b.product_id = v_to
      AND b.color_id IS NOT DISTINCT FROM a.color_id;

    UPDATE product_variant_leather
    SET product_id = v_to
    WHERE product_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 6) Conteos kiosko: si choca, se queda el del 344
  -- -------------------------------------------------------------------------
  IF to_regclass('public.kiosco_physical_count_item') IS NOT NULL THEN
    DELETE FROM kiosco_physical_count_item a
    USING kiosco_physical_count_item b
    WHERE a.product_id = v_from
      AND b.product_id = v_to
      AND b.count_id = a.count_id
      AND b.color_id IS NOT DISTINCT FROM a.color_id;

    UPDATE kiosco_physical_count_item
    SET product_id = v_to, updated_at = NOW()
    WHERE product_id = v_from;
  END IF;

  IF to_regclass('public.kiosco_internal_count_item') IS NOT NULL THEN
    DELETE FROM kiosco_internal_count_item a
    USING kiosco_internal_count_item b
    WHERE a.product_id = v_from
      AND b.product_id = v_to
      AND b.internal_count_id = a.internal_count_id
      AND b.color_id IS NOT DISTINCT FROM a.color_id;

    UPDATE kiosco_internal_count_item
    SET product_id = v_to
    WHERE product_id = v_from;
  END IF;

  IF to_regclass('public.kiosco_opening_inventory_item') IS NOT NULL THEN
    DELETE FROM kiosco_opening_inventory_item a
    USING kiosco_opening_inventory_item b
    WHERE a.product_id = v_from
      AND b.product_id = v_to
      AND b.opening_inventory_id = a.opening_inventory_id
      AND b.color_id IS NOT DISTINCT FROM a.color_id
      AND COALESCE(b.hardware_condition, '') = COALESCE(a.hardware_condition, '');

    UPDATE kiosco_opening_inventory_item
    SET product_id = v_to
    WHERE product_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 7) Remapeo simple (sin unique conflict esperado)
  -- -------------------------------------------------------------------------
  UPDATE production_order_item SET product_id = v_to WHERE product_id = v_from;
  UPDATE task SET product_id = v_to, product_code = v_to_code,
                  product_name = COALESCE(v_to_name, product_name)
  WHERE product_id = v_from;
  UPDATE task_item SET product_id = v_to, product_code = v_to_code,
                       product_name = COALESCE(v_to_name, product_name)
  WHERE product_id = v_from;

  UPDATE kiosk_sale_item
  SET product_id = v_to,
      product_code = v_to_code,
      product_name = COALESCE(v_to_name, product_name)
  WHERE product_id = v_from;

  IF to_regclass('public.online_sale') IS NOT NULL THEN
    UPDATE online_sale
    SET product_id = v_to,
        product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.online_sale_item') IS NOT NULL THEN
    UPDATE online_sale_item
    SET product_id = v_to,
        product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.online_sale_return_line') IS NOT NULL THEN
    UPDATE online_sale_return_line
    SET product_id = v_to,
        product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE product_id = v_from;
  END IF;

  IF to_regclass('public.inventory_adjustment') IS NOT NULL THEN
    UPDATE inventory_adjustment SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.inventory_transfer') IS NOT NULL THEN
    UPDATE inventory_transfer SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.product_inventory_kardex') IS NOT NULL THEN
    UPDATE product_inventory_kardex SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.product_fifo_batch') IS NOT NULL THEN
    UPDATE product_fifo_batch SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.qa_record') IS NOT NULL THEN
    UPDATE qa_record SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.return_inventory') IS NOT NULL THEN
    UPDATE return_inventory
    SET product_id = v_to,
        product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.bom') IS NOT NULL THEN
    UPDATE bom SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.internal_shipment_request_line') IS NOT NULL THEN
    UPDATE internal_shipment_request_line SET product_id = v_to WHERE product_id = v_from;
  END IF;
  IF to_regclass('public.kiosk_exchange_slip') IS NOT NULL THEN
    UPDATE kiosk_exchange_slip SET returned_product_id = v_to WHERE returned_product_id = v_from;
    UPDATE kiosk_exchange_slip SET given_product_id = v_to WHERE given_product_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 8) Textos denormalizados por código (por si quedó BD-500 sin product_id)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.kiosk_sale_item') IS NOT NULL THEN
    UPDATE kiosk_sale_item
    SET product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE upper(product_code) = upper(v_from_code);
  END IF;
  IF to_regclass('public.task') IS NOT NULL THEN
    UPDATE task
    SET product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE upper(product_code) = upper(v_from_code);
  END IF;
  IF to_regclass('public.task_item') IS NOT NULL THEN
    UPDATE task_item
    SET product_code = v_to_code,
        product_name = COALESCE(v_to_name, product_name)
    WHERE upper(product_code) = upper(v_from_code);
  END IF;

  -- -------------------------------------------------------------------------
  -- 9) Desactivar / renombrar 351 (no borrar aún hasta verificar 0 refs)
  -- -------------------------------------------------------------------------
  UPDATE product
  SET code = left(v_from_code || '__MERGED_INTO_' || v_to::text, 30),
      status = 'I',
      name = COALESCE(name, '') || ' [FUSIONADO→' || v_to::text || ']',
      updated_at = NOW()
  WHERE id = v_from;

  -- -------------------------------------------------------------------------
  -- 10) Verificación: no deben quedar product_id = 351
  -- -------------------------------------------------------------------------
  SELECT COALESCE(SUM(cnt), 0) INTO v_left
  FROM (
    SELECT COUNT(*) AS cnt FROM kiosco_stock WHERE product_id = v_from
    UNION ALL SELECT COUNT(*) FROM product_inventory_location WHERE product_id = v_from
    UNION ALL SELECT COUNT(*) FROM product_shipment_detail WHERE product_id = v_from
    UNION ALL SELECT COUNT(*) FROM production_order_item WHERE product_id = v_from
    UNION ALL SELECT COUNT(*) FROM kiosk_sale_item WHERE product_id = v_from
  ) x;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'Aún quedan % referencias a product_id=% después del merge. ROLLBACK.', v_left, v_from;
  END IF;

  RAISE NOTICE 'Merge OK. Producto % desactivado. Revisa conteos y luego puedes DELETE si quieres.', v_from;
END $$;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Verificación rápida post-merge (debe dar 0 en el origen)
-- Cambia el ID si usaste otros.
-- SELECT 'kiosco_stock' t, COUNT(*) c FROM kiosco_stock WHERE product_id = 351
-- UNION ALL SELECT 'product_inventory_location', COUNT(*) FROM product_inventory_location WHERE product_id = 351
-- UNION ALL SELECT 'product_shipment_detail', COUNT(*) FROM product_shipment_detail WHERE product_id = 351
-- UNION ALL SELECT 'kiosk_sale_item', COUNT(*) FROM kiosk_sale_item WHERE product_id = 351
-- UNION ALL SELECT 'production_order_item', COUNT(*) FROM production_order_item WHERE product_id = 351;

-- Si todo OK:
COMMIT;
-- Si algo falla / dudas:
-- ROLLBACK;

-- Opcional: borrar el catálogo 351 solo cuando el audit diga 0 refs
-- DELETE FROM product WHERE id = 351 AND status = 'I' AND code LIKE '%__MERGED_INTO_%';
