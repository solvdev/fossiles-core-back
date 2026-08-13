-- =============================================================================
-- Cuenta usos de un color (por ID y por nombre) en public.
--
-- DataGrip: selecciona TODO el archivo y ejecuta.
-- Default: color 31 (TAMARINDO ACABADO ESPARTACO)
-- =============================================================================

DO $$
DECLARE
  v_color_id BIGINT := 31;             -- << ID a auditar
  v_color_name TEXT := NULL;
  r RECORD;
  v_sql TEXT;
  v_count BIGINT;
  v_scanned INT := 0;
BEGIN
  DROP TABLE IF EXISTS tmp_color_id_usage;
  CREATE TEMP TABLE tmp_color_id_usage (
    kind         TEXT NOT NULL,   -- ID | NAME | META
    table_name   TEXT NOT NULL,
    column_name  TEXT NOT NULL,
    row_count    BIGINT NOT NULL
  );

  SELECT c.name INTO v_color_name FROM colors c WHERE c.id = v_color_id;

  INSERT INTO tmp_color_id_usage(kind, table_name, column_name, row_count)
  VALUES (
    'META',
    'colors',
    CASE WHEN v_color_name IS NULL THEN 'NO_EXISTE' ELSE v_color_name END,
    CASE WHEN v_color_name IS NULL THEN 0 ELSE 1 END
  );

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
        c.column_name = 'color_id'
        OR c.column_name LIKE '%\_color\_id' ESCAPE '\'
      )
      -- material_color_id suele ser catálogo distinto
      AND c.column_name NOT IN ('material_color_id')
      AND c.table_name <> 'colors'
    ORDER BY c.table_name, c.column_name
  LOOP
    v_scanned := v_scanned + 1;
    v_sql := format(
      'SELECT COUNT(*) FROM public.%I WHERE %I = $1',
      r.table_name, r.column_name
    );
    EXECUTE v_sql INTO v_count USING v_color_id;
    IF v_count > 0 THEN
      INSERT INTO tmp_color_id_usage(kind, table_name, column_name, row_count)
      VALUES ('ID', r.table_name, r.column_name, v_count);
    END IF;
  END LOOP;

  INSERT INTO tmp_color_id_usage(kind, table_name, column_name, row_count)
  VALUES ('META', '_scan', 'numeric_color_id_columns', v_scanned);

  IF v_color_name IS NOT NULL AND btrim(v_color_name) <> '' THEN
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
        AND c.column_name IN ('color_name', 'color')
        AND c.table_name <> 'colors'
      ORDER BY c.table_name, c.column_name
    LOOP
      v_scanned := v_scanned + 1;
      v_sql := format(
        'SELECT COUNT(*) FROM public.%I WHERE upper(btrim(%I::text)) = upper(btrim($1))',
        r.table_name, r.column_name
      );
      BEGIN
        EXECUTE v_sql INTO v_count USING v_color_name;
        IF v_count > 0 THEN
          INSERT INTO tmp_color_id_usage(kind, table_name, column_name, row_count)
          VALUES ('NAME', r.table_name, r.column_name, v_count);
        END IF;
      EXCEPTION WHEN others THEN
        NULL;
      END;
    END LOOP;

    INSERT INTO tmp_color_id_usage(kind, table_name, column_name, row_count)
    VALUES ('META', '_scan', 'text_color_name_columns', v_scanned);
  END IF;

  RAISE NOTICE 'Audit listo color_id=% name=%', v_color_id, v_color_name;
END $$;

SELECT kind, table_name, column_name, row_count
FROM tmp_color_id_usage
ORDER BY
  CASE kind WHEN 'META' THEN 0 WHEN 'ID' THEN 1 ELSE 2 END,
  row_count DESC,
  table_name,
  column_name;

SELECT
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'ID'), 0) AS rows_by_color_id,
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'NAME'), 0) AS rows_by_color_name
FROM tmp_color_id_usage;
