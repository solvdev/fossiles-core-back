-- =============================================================================
-- Cuenta usos de un producto (por ID y por código) en public.
--
-- DataGrip: selecciona TODO el archivo y ejecuta (Ctrl+Enter / Run).
-- Si corres solo el SELECT final sin el DO, verás vacío / 0.
-- =============================================================================

DO $$
DECLARE
  v_product_id BIGINT := 351;          -- << ID a auditar
  v_product_code TEXT := NULL;         -- se llena solo desde product
  r RECORD;
  v_sql TEXT;
  v_count BIGINT;
  v_scanned INT := 0;
BEGIN
  DROP TABLE IF EXISTS tmp_product_id_usage;
  CREATE TEMP TABLE tmp_product_id_usage (
    kind         TEXT NOT NULL,   -- ID | CODE | META
    table_name   TEXT NOT NULL,
    column_name  TEXT NOT NULL,
    row_count    BIGINT NOT NULL
  );

  SELECT p.code INTO v_product_code FROM product p WHERE p.id = v_product_id;

  INSERT INTO tmp_product_id_usage(kind, table_name, column_name, row_count)
  VALUES (
    'META',
    'product',
    CASE WHEN v_product_code IS NULL THEN 'NO_EXISTE' ELSE v_product_code END,
    CASE WHEN v_product_code IS NULL THEN 0 ELSE 1 END
  );

  -- 1) Columnas numéricas product_id / *_product_id
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
        c.column_name = 'product_id'
        OR c.column_name LIKE '%\_product\_id' ESCAPE '\'
      )
      AND c.column_name NOT LIKE 'production\_%' ESCAPE '\'
      AND c.column_name NOT LIKE 'product\_shipment%' ESCAPE '\'
      AND c.table_name <> 'product'
    ORDER BY c.table_name, c.column_name
  LOOP
    v_scanned := v_scanned + 1;
    v_sql := format(
      'SELECT COUNT(*) FROM public.%I WHERE %I = $1',
      r.table_name, r.column_name
    );
    EXECUTE v_sql INTO v_count USING v_product_id;
    IF v_count > 0 THEN
      INSERT INTO tmp_product_id_usage(kind, table_name, column_name, row_count)
      VALUES ('ID', r.table_name, r.column_name, v_count);
    END IF;
  END LOOP;

  INSERT INTO tmp_product_id_usage(kind, table_name, column_name, row_count)
  VALUES ('META', '_scan', 'numeric_product_id_columns', v_scanned);

  -- 2) Si existe el código, busca en columnas product_code (texto)
  IF v_product_code IS NOT NULL AND btrim(v_product_code) <> '' THEN
    v_scanned := 0;
    FOR r IN
      SELECT c.table_name, c.column_name
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.table_schema = c.table_schema
       AND t.table_name = c.table_name
      WHERE c.table_schema = 'public'
        AND t.table_type = 'BASE TABLE'
        AND c.data_type IN ('character varying', 'character', 'text')
        AND c.column_name IN ('product_code', 'code')
        AND c.table_name <> 'product'
      ORDER BY c.table_name, c.column_name
    LOOP
      v_scanned := v_scanned + 1;
      v_sql := format(
        'SELECT COUNT(*) FROM public.%I WHERE upper(btrim(%I::text)) = upper(btrim($1))',
        r.table_name, r.column_name
      );
      BEGIN
        EXECUTE v_sql INTO v_count USING v_product_code;
        IF v_count > 0 THEN
          INSERT INTO tmp_product_id_usage(kind, table_name, column_name, row_count)
          VALUES ('CODE', r.table_name, r.column_name, v_count);
        END IF;
      EXCEPTION WHEN others THEN
        -- ignorar tablas raras
        NULL;
      END;
    END LOOP;

    INSERT INTO tmp_product_id_usage(kind, table_name, column_name, row_count)
    VALUES ('META', '_scan', 'text_product_code_columns', v_scanned);
  END IF;

  RAISE NOTICE 'Audit listo product_id=% code=%', v_product_id, v_product_code;
END $$;

-- Meta + hallazgos
SELECT kind, table_name, column_name, row_count
FROM tmp_product_id_usage
ORDER BY
  CASE kind WHEN 'META' THEN 0 WHEN 'ID' THEN 1 ELSE 2 END,
  row_count DESC,
  table_name,
  column_name;

-- Totales (sin META)
SELECT
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'ID'), 0) AS rows_by_product_id,
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'CODE'), 0) AS rows_by_product_code
FROM tmp_product_id_usage;
