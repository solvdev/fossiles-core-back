-- =============================================================================
-- Fusionar color 31 (TAMARINDO ACABADO ESPARTACO) → 43 (TOSTADO ESPARTACO)
--
-- 1) Corre scripts/audit-color-id-usage-counts.sql (31 y 43).
-- 2) Ejecuta TODO este archivo en UNA sola transacción.
-- 3) Requiere flag admin de kiosco_movement si hay stock a fusionar.
-- 4) Backup / snapshot antes.
-- =============================================================================

BEGIN;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

DO $$
DECLARE
  v_from BIGINT := 31;
  v_to   BIGINT := 43;
  v_from_name TEXT;
  v_to_name   TEXT;
  v_left BIGINT;
BEGIN
  SELECT name INTO v_from_name FROM colors WHERE id = v_from;
  SELECT name INTO v_to_name FROM colors WHERE id = v_to;

  IF v_from_name IS NULL THEN
    RAISE EXCEPTION 'Color origen % no existe', v_from;
  END IF;
  IF v_to_name IS NULL THEN
    RAISE EXCEPTION 'Color destino % no existe', v_to;
  END IF;

  RAISE NOTICE 'Merge color % (%) → % (%)', v_from, v_from_name, v_to, v_to_name;

  -- -------------------------------------------------------------------------
  -- 1) kiosco_stock
  -- -------------------------------------------------------------------------
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
      ON b.color_id = v_to
     AND b.location_id = a.location_id
     AND b.product_id = a.product_id
     AND b.hardware_condition = a.hardware_condition
    WHERE a.color_id = v_from
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

  UPDATE kiosco_stock
  SET color_id = v_to,
      updated_at = NOW(),
      last_updated_at = NOW()
  WHERE color_id = v_from;

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
      ON b.color_id = v_to
     AND b.product_id = a.product_id
     AND b.location_id = a.location_id
    WHERE a.color_id = v_from
  ),
  upd AS (
    UPDATE product_inventory_location pil
    SET quantity = p.to_qty + p.from_qty,
        sizes_data = p.keep_sizes,
        updated_at = NOW()
    FROM pairs p
    WHERE pil.id = p.to_id
    RETURNING pil.id
  )
  DELETE FROM product_inventory_location pil
  USING pairs p
  WHERE pil.id = p.from_id;

  UPDATE product_inventory_location
  SET color_id = v_to, updated_at = NOW()
  WHERE color_id = v_from;

  -- -------------------------------------------------------------------------
  -- 3) product_shipment_detail
  -- -------------------------------------------------------------------------
  WITH pairs AS (
    SELECT a.id AS from_id, b.id AS to_id,
           COALESCE(a.quantity, 0) AS from_qty,
           COALESCE(b.quantity, 0) AS to_qty
    FROM product_shipment_detail a
    JOIN product_shipment_detail b
      ON b.color_id = v_to
     AND b.shipment_id = a.shipment_id
     AND b.product_id = a.product_id
     AND COALESCE(b.size_label, '') = COALESCE(a.size_label, '')
     AND COALESCE(b.hardware_condition, '') = COALESCE(a.hardware_condition, '')
    WHERE a.color_id = v_from
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
  SET color_id = v_to
  WHERE color_id = v_from;

  -- -------------------------------------------------------------------------
  -- 4) product_variant_leather / conteos (si choca, gana el 43)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.product_variant_leather') IS NOT NULL THEN
    DELETE FROM product_variant_leather a
    USING product_variant_leather b
    WHERE a.color_id = v_from
      AND b.color_id = v_to
      AND b.product_id = a.product_id;

    UPDATE product_variant_leather SET color_id = v_to WHERE color_id = v_from;
  END IF;

  IF to_regclass('public.kiosco_physical_count_item') IS NOT NULL THEN
    DELETE FROM kiosco_physical_count_item a
    USING kiosco_physical_count_item b
    WHERE a.color_id = v_from
      AND b.color_id = v_to
      AND b.count_id = a.count_id
      AND b.product_id = a.product_id;

    UPDATE kiosco_physical_count_item
    SET color_id = v_to, updated_at = NOW()
    WHERE color_id = v_from;
  END IF;

  IF to_regclass('public.kiosco_internal_count_item') IS NOT NULL THEN
    DELETE FROM kiosco_internal_count_item a
    USING kiosco_internal_count_item b
    WHERE a.color_id = v_from
      AND b.color_id = v_to
      AND b.internal_count_id = a.internal_count_id
      AND b.product_id = a.product_id;

    UPDATE kiosco_internal_count_item SET color_id = v_to WHERE color_id = v_from;
  END IF;

  IF to_regclass('public.kiosco_opening_inventory_item') IS NOT NULL THEN
    DELETE FROM kiosco_opening_inventory_item a
    USING kiosco_opening_inventory_item b
    WHERE a.color_id = v_from
      AND b.color_id = v_to
      AND b.opening_inventory_id = a.opening_inventory_id
      AND b.product_id = a.product_id
      AND COALESCE(b.hardware_condition, '') = COALESCE(a.hardware_condition, '');

    UPDATE kiosco_opening_inventory_item SET color_id = v_to WHERE color_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 5) Remapeo simple
  -- -------------------------------------------------------------------------
  UPDATE production_order_item SET color_id = v_to WHERE color_id = v_from;
  UPDATE task SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;
  UPDATE task_item SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;

  UPDATE kiosk_sale_item
  SET color_id = v_to, color_name = v_to_name
  WHERE color_id = v_from;

  IF to_regclass('public.online_sale') IS NOT NULL THEN
    UPDATE online_sale SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.online_sale_item') IS NOT NULL THEN
    UPDATE online_sale_item SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.online_sale_return_line') IS NOT NULL THEN
    UPDATE online_sale_return_line SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;
  END IF;

  IF to_regclass('public.inventory_adjustment') IS NOT NULL THEN
    UPDATE inventory_adjustment SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.inventory_transfer') IS NOT NULL THEN
    UPDATE inventory_transfer SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.product_inventory_kardex') IS NOT NULL THEN
    UPDATE product_inventory_kardex SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.product_fifo_batch') IS NOT NULL THEN
    UPDATE product_fifo_batch SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.qa_record') IS NOT NULL THEN
    UPDATE qa_record SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.return_inventory') IS NOT NULL THEN
    UPDATE return_inventory SET color_id = v_to, color_name = v_to_name WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.bom') IS NOT NULL THEN
    UPDATE bom SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.internal_shipment_request_line') IS NOT NULL THEN
    UPDATE internal_shipment_request_line SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.production_order_warehouse_unit') IS NOT NULL THEN
    UPDATE production_order_warehouse_unit SET color_id = v_to WHERE color_id = v_from;
  END IF;
  IF to_regclass('public.kiosk_exchange_slip') IS NOT NULL THEN
    UPDATE kiosk_exchange_slip SET returned_color_id = v_to WHERE returned_color_id = v_from;
    UPDATE kiosk_exchange_slip SET given_color_id = v_to WHERE given_color_id = v_from;
  END IF;

  -- Nombres denormalizados sin color_id
  UPDATE kiosk_sale_item
  SET color_name = v_to_name
  WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));

  UPDATE task
  SET color_name = v_to_name
  WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));

  UPDATE task_item
  SET color_name = v_to_name
  WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));

  IF to_regclass('public.online_sale') IS NOT NULL THEN
    UPDATE online_sale
    SET color_name = v_to_name
    WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));
  END IF;
  IF to_regclass('public.online_sale_item') IS NOT NULL THEN
    UPDATE online_sale_item
    SET color_name = v_to_name
    WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));
  END IF;
  IF to_regclass('public.return_inventory') IS NOT NULL THEN
    UPDATE return_inventory
    SET color_name = v_to_name
    WHERE upper(btrim(color_name)) = upper(btrim(v_from_name));
  END IF;

  -- -------------------------------------------------------------------------
  -- 6) Renombrar color 31 para que no se vuelva a usar
  -- -------------------------------------------------------------------------
  UPDATE colors
  SET name = left(v_from_name || ' [FUSIONADO→' || v_to::text || ']', 50)
  WHERE id = v_from;

  SELECT COALESCE(SUM(cnt), 0) INTO v_left
  FROM (
    SELECT COUNT(*) AS cnt FROM kiosco_stock WHERE color_id = v_from
    UNION ALL SELECT COUNT(*) FROM product_inventory_location WHERE color_id = v_from
    UNION ALL SELECT COUNT(*) FROM product_shipment_detail WHERE color_id = v_from
    UNION ALL SELECT COUNT(*) FROM production_order_item WHERE color_id = v_from
    UNION ALL SELECT COUNT(*) FROM kiosk_sale_item WHERE color_id = v_from
  ) x;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'Aún quedan % referencias a color_id=% después del merge. ROLLBACK.', v_left, v_from;
  END IF;

  RAISE NOTICE 'Merge color OK. Color % renombrado. Verifica audit y opcionalmente DELETE.', v_from;
END $$;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

COMMIT;
-- Si falla: ROLLBACK;

-- Opcional (solo si audit del 31 = 0):
-- DELETE FROM colors WHERE id = 31 AND name LIKE '%[FUSIONADO→43]%';
