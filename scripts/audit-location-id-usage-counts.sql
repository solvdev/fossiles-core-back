-- =============================================================================
-- Cuenta usos de un location_id (kiosko / bodega) en public.
--
-- DataGrip: selecciona TODO el archivo y ejecuta.
-- Default: 20 (PDP_COBAN) vs 21 (MAGDACOBAN)
-- =============================================================================

DO $$
DECLARE
  v_from BIGINT := 20;                 -- << ID origen
  v_to   BIGINT := 21;                 -- << ID destino (solo para comparar)
  v_from_code TEXT;
  v_from_name TEXT;
  v_to_code TEXT;
  v_to_name TEXT;
  r RECORD;
  v_sql TEXT;
  v_count BIGINT;
  v_scanned INT := 0;
BEGIN
  DROP TABLE IF EXISTS tmp_location_id_usage;
  CREATE TEMP TABLE tmp_location_id_usage (
    kind         TEXT NOT NULL,   -- FROM | TO | META
    table_name   TEXT NOT NULL,
    column_name  TEXT NOT NULL,
    row_count    BIGINT NOT NULL
  );

  SELECT l.code, l.name INTO v_from_code, v_from_name FROM locations l WHERE l.id = v_from;
  SELECT l.code, l.name INTO v_to_code, v_to_name FROM locations l WHERE l.id = v_to;

  INSERT INTO tmp_location_id_usage(kind, table_name, column_name, row_count)
  VALUES
    ('META', 'locations', COALESCE(v_from_code, 'NO_EXISTE') || ' id=' || v_from, CASE WHEN v_from_code IS NULL THEN 0 ELSE 1 END),
    ('META', 'locations', COALESCE(v_to_code, 'NO_EXISTE') || ' id=' || v_to, CASE WHEN v_to_code IS NULL THEN 0 ELSE 1 END);

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
    v_scanned := v_scanned + 1;
    v_sql := format(
      'SELECT COUNT(*) FROM public.%I WHERE %I = $1',
      r.table_name, r.column_name
    );
    EXECUTE v_sql INTO v_count USING v_from;
    IF v_count > 0 THEN
      INSERT INTO tmp_location_id_usage(kind, table_name, column_name, row_count)
      VALUES ('FROM', r.table_name, r.column_name, v_count);
    END IF;
    EXECUTE v_sql INTO v_count USING v_to;
    IF v_count > 0 THEN
      INSERT INTO tmp_location_id_usage(kind, table_name, column_name, row_count)
      VALUES ('TO', r.table_name, r.column_name, v_count);
    END IF;
  END LOOP;

  INSERT INTO tmp_location_id_usage(kind, table_name, column_name, row_count)
  VALUES ('META', '_scan', 'numeric_location_id_columns', v_scanned);

  RAISE NOTICE 'Audit listo from=% (%) to=% (%)', v_from, v_from_code, v_to, v_to_code;
END $$;

SELECT kind, table_name, column_name, row_count
FROM tmp_location_id_usage
ORDER BY
  CASE kind WHEN 'META' THEN 0 WHEN 'FROM' THEN 1 ELSE 2 END,
  row_count DESC,
  table_name,
  column_name;

SELECT
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'FROM'), 0) AS rows_on_from_id,
  COALESCE(SUM(row_count) FILTER (WHERE kind = 'TO'), 0) AS rows_on_to_id
FROM tmp_location_id_usage;
