-- =============================================================================
-- Reasignar kiosko 20 (PDP_COBAN) → 21 (MAGDACOBAN)
--
-- 1) Corre primero scripts/audit-location-id-usage-counts.sql (20 y 21).
-- 2) Ejecuta TODO este archivo en UNA sola transacción (BEGIN…COMMIT).
-- 3) Requiere la función prevent_kiosco_movement_mutation con flag admin
--    (scripts/migration-kiosco-movement-admin-delete.sql).
-- 4) Haz backup / snapshot antes.
-- 5) No borra el registro locations.id = 20; solo remapea FKs.
-- =============================================================================

BEGIN;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

CREATE OR REPLACE FUNCTION pg_temp.merge_qty_json(p_a text, p_b text)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
  v_a jsonb;
  v_b jsonb;
  v_out jsonb := '{}'::jsonb;
  r record;
  v_sum numeric;
BEGIN
  BEGIN v_a := p_a::jsonb; EXCEPTION WHEN others THEN v_a := NULL; END;
  BEGIN v_b := p_b::jsonb; EXCEPTION WHEN others THEN v_b := NULL; END;
  IF v_a IS NULL OR jsonb_typeof(v_a) IS DISTINCT FROM 'object' THEN
    v_a := '{}'::jsonb;
  END IF;
  IF v_b IS NULL OR jsonb_typeof(v_b) IS DISTINCT FROM 'object' THEN
    v_b := '{}'::jsonb;
  END IF;
  IF v_a = '{}'::jsonb AND v_b = '{}'::jsonb THEN
    RETURN COALESCE(NULLIF(btrim(p_b), ''), NULLIF(btrim(p_a), ''));
  END IF;
  FOR r IN SELECT key, value FROM jsonb_each(v_a)
  LOOP
    v_out := v_out || jsonb_build_object(r.key, r.value);
  END LOOP;
  FOR r IN SELECT key, value FROM jsonb_each(v_b)
  LOOP
    IF (v_out ? r.key)
       AND jsonb_typeof(v_out -> r.key) = 'number'
       AND jsonb_typeof(r.value) = 'number' THEN
      v_sum := (v_out ->> r.key)::numeric + (r.value #>> '{}')::numeric;
      v_out := v_out || jsonb_build_object(r.key, v_sum);
    ELSE
      v_out := v_out || jsonb_build_object(r.key, r.value);
    END IF;
  END LOOP;
  RETURN v_out::text;
END;
$$;

DO $$
DECLARE
  v_from BIGINT := 20;
  v_to   BIGINT := 21;
  v_from_code TEXT;
  v_to_code TEXT;
  v_left BIGINT;
  r RECORD;
  v_sql TEXT;
  v_count BIGINT;
  v_updated INT := 0;
BEGIN
  SELECT code INTO v_from_code FROM locations WHERE id = v_from;
  SELECT code INTO v_to_code FROM locations WHERE id = v_to;

  IF v_from_code IS NULL THEN
    RAISE EXCEPTION 'Location origen % no existe', v_from;
  END IF;
  IF v_to_code IS NULL THEN
    RAISE EXCEPTION 'Location destino % no existe', v_to;
  END IF;

  RAISE NOTICE 'Merge location % (%) → % (%)', v_from, v_from_code, v_to, v_to_code;

  IF EXISTS (
       SELECT 1 FROM kiosk_cash_session
       WHERE kiosk_location_id = v_from AND status = 'OPEN'
     )
     AND EXISTS (
       SELECT 1 FROM kiosk_cash_session
       WHERE kiosk_location_id = v_to AND status = 'OPEN'
     ) THEN
    RAISE EXCEPTION
      'Ambos kioskos tienen sesión de caja OPEN. Cierra una antes de fusionar.';
  END IF;

  -- -------------------------------------------------------------------------
  -- 1) kiosco_stock: consolidar misma clave; remapar movimientos
  -- -------------------------------------------------------------------------
  WITH pairs AS (
    SELECT
      a.id AS from_id,
      b.id AS to_id,
      COALESCE(a.current_stock, 0) AS from_stock,
      COALESCE(b.current_stock, 0) AS to_stock,
      GREATEST(COALESCE(a.minimum_stock, 0), COALESCE(b.minimum_stock, 0)) AS min_stock,
      pg_temp.merge_qty_json(a.sizes_data, b.sizes_data) AS keep_sizes
    FROM kiosco_stock a
    JOIN kiosco_stock b
      ON b.location_id = v_to
     AND b.product_id = a.product_id
     AND b.color_id IS NOT DISTINCT FROM a.color_id
     AND b.hardware_condition = a.hardware_condition
    WHERE a.location_id = v_from
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
  SET location_id = v_to,
      updated_at = NOW(),
      last_updated_at = NOW()
  WHERE location_id = v_from;

  -- -------------------------------------------------------------------------
  -- 2) product_inventory_location
  -- -------------------------------------------------------------------------
  IF to_regclass('public.product_inventory_location') IS NOT NULL THEN
    WITH pairs AS (
      SELECT a.id AS from_id, b.id AS to_id,
             COALESCE(a.quantity, 0) AS from_qty,
             COALESCE(b.quantity, 0) AS to_qty,
             pg_temp.merge_qty_json(a.sizes_data, b.sizes_data) AS keep_sizes
      FROM product_inventory_location a
      JOIN product_inventory_location b
        ON b.location_id = v_to
       AND b.product_id = a.product_id
       AND b.color_id IS NOT DISTINCT FROM a.color_id
      WHERE a.location_id = v_from
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
    SET location_id = v_to, updated_at = NOW()
    WHERE location_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 3) inventory_location (materiales)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.inventory_location') IS NOT NULL THEN
    WITH pairs AS (
      SELECT a.id AS from_id, b.id AS to_id,
             COALESCE(a.quantity, 0) AS from_qty,
             COALESCE(b.quantity, 0) AS to_qty
      FROM inventory_location a
      JOIN inventory_location b
        ON b.location_id = v_to
       AND b.material_id = a.material_id
      WHERE a.location_id = v_from
    ),
    upd AS (
      UPDATE inventory_location il
      SET quantity = p.to_qty + p.from_qty,
          updated_at = NOW()
      FROM pairs p
      WHERE il.id = p.to_id
      RETURNING il.id
    )
    DELETE FROM inventory_location il
    USING pairs p
    WHERE il.id = p.from_id;

    UPDATE inventory_location
    SET location_id = v_to, updated_at = NOW()
    WHERE location_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 4) kiosco_internal_count (unique location_id + count_date)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.kiosco_internal_count') IS NOT NULL THEN
    IF to_regclass('public.kiosco_internal_count_item') IS NOT NULL THEN
      DELETE FROM kiosco_internal_count_item a
      USING kiosco_internal_count src,
            kiosco_internal_count dst,
            kiosco_internal_count_item b
      WHERE src.location_id = v_from
        AND dst.location_id = v_to
        AND dst.count_date = src.count_date
        AND a.internal_count_id = src.id
        AND b.internal_count_id = dst.id
        AND b.product_id = a.product_id
        AND b.color_id IS NOT DISTINCT FROM a.color_id;

      UPDATE kiosco_internal_count_item a
      SET internal_count_id = dst.id,
          updated_at = NOW()
      FROM kiosco_internal_count src,
           kiosco_internal_count dst
      WHERE src.location_id = v_from
        AND dst.location_id = v_to
        AND dst.count_date = src.count_date
        AND a.internal_count_id = src.id;
    END IF;

    DELETE FROM kiosco_internal_count src
    USING kiosco_internal_count dst
    WHERE src.location_id = v_from
      AND dst.location_id = v_to
      AND dst.count_date = src.count_date;

    UPDATE kiosco_internal_count
    SET location_id = v_to
    WHERE location_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 5) kiosk_exchange_slip_sequence (PK location + year)
  -- -------------------------------------------------------------------------
  IF to_regclass('public.kiosk_exchange_slip_sequence') IS NOT NULL THEN
    UPDATE kiosk_exchange_slip_sequence dst
    SET last_number = GREATEST(dst.last_number, src.last_number)
    FROM kiosk_exchange_slip_sequence src
    WHERE src.kiosk_location_id = v_from
      AND dst.kiosk_location_id = v_to
      AND dst.sequence_year = src.sequence_year;

    DELETE FROM kiosk_exchange_slip_sequence src
    USING kiosk_exchange_slip_sequence dst
    WHERE src.kiosk_location_id = v_from
      AND dst.kiosk_location_id = v_to
      AND dst.sequence_year = src.sequence_year;

    UPDATE kiosk_exchange_slip_sequence
    SET kiosk_location_id = v_to
    WHERE kiosk_location_id = v_from;
  END IF;

  -- -------------------------------------------------------------------------
  -- 6) Resto de columnas *_location_id (incluye kiosco_movement origin/dest)
  -- -------------------------------------------------------------------------
  FOR r IN
    SELECT c.table_name, c.column_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema
     AND t.table_name = c.table_name
    WHERE c.table_schema = 'public'
      AND t.table_type = 'BASE TABLE'
      AND c.data_type IN ('bigint', 'integer', 'smallint', 'numeric')
      AND (
        c.column_name = 'location_id'
        OR c.column_name LIKE '%\_location\_id' ESCAPE '\'
      )
      AND NOT (c.table_name = 'locations' AND c.column_name = 'id')
    ORDER BY c.table_name, c.column_name
  LOOP
    v_sql := format(
      'UPDATE public.%I SET %I = $1 WHERE %I = $2',
      r.table_name, r.column_name, r.column_name
    );
    EXECUTE v_sql USING v_to, v_from;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    IF v_count > 0 THEN
      v_updated := v_updated + 1;
      RAISE NOTICE 'UPDATE %.% : % filas', r.table_name, r.column_name, v_count;
    END IF;
  END LOOP;

  -- -------------------------------------------------------------------------
  -- 7) Verificar que no queden FKs en 20
  -- -------------------------------------------------------------------------
  v_left := 0;
  FOR r IN
    SELECT c.table_name, c.column_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema
     AND t.table_name = c.table_name
    WHERE c.table_schema = 'public'
      AND t.table_type = 'BASE TABLE'
      AND c.data_type IN ('bigint', 'integer', 'smallint', 'numeric')
      AND (
        c.column_name = 'location_id'
        OR c.column_name LIKE '%\_location\_id' ESCAPE '\'
      )
      AND NOT (c.table_name = 'locations' AND c.column_name = 'id')
  LOOP
    v_sql := format(
      'SELECT COUNT(*) FROM public.%I WHERE %I = $1',
      r.table_name, r.column_name
    );
    EXECUTE v_sql INTO v_count USING v_from;
    IF v_count > 0 THEN
      v_left := v_left + v_count;
      RAISE NOTICE 'QUEDAN % en %.%', v_count, r.table_name, r.column_name;
    END IF;
  END LOOP;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'Quedan % referencias al location %', v_left, v_from;
  END IF;

  RAISE NOTICE 'Merge OK. Columnas tocadas=% . Location % (%) sigue existiendo vacía.',
    v_updated, v_from, v_from_code;
END $$;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Si todo OK:
COMMIT;
-- Si algo falla / dudas:
-- ROLLBACK;
