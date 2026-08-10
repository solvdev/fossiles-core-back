-- Auditoría: filas duplicadas en kiosco_stock (incl. color_id NULL).
-- Misma clave lógica que uq_kiosco_stock_loc_prod_color_hw (COALESCE color).
-- Ejecutar en PostgreSQL tras migración o ante sospecha de doble stock.

-- =============================================================================
-- 1) Duplicados por (location, product, color null-safe, hardware)
-- =============================================================================
SELECT
  ks.location_id,
  l.code AS location_code,
  ks.product_id,
  p.code AS product_code,
  COALESCE(ks.color_id, -1) AS color_key,
  ks.color_id,
  ks.hardware_condition,
  COUNT(*) AS filas,
  ARRAY_AGG(ks.id ORDER BY ks.id) AS stock_ids,
  SUM(COALESCE(ks.current_stock, 0)) AS stock_total,
  ARRAY_AGG(COALESCE(ks.sizes_data, '') ORDER BY ks.id) AS sizes_data_list
FROM kiosco_stock ks
LEFT JOIN locations l ON l.id = ks.location_id
LEFT JOIN product p ON p.id = ks.product_id
GROUP BY
  ks.location_id,
  l.code,
  ks.product_id,
  p.code,
  COALESCE(ks.color_id, -1),
  ks.color_id,
  ks.hardware_condition
HAVING COUNT(*) > 1
ORDER BY filas DESC, ks.location_id, ks.product_id;

-- =============================================================================
-- 2) Pares con IS NOT DISTINCT FROM (validación null-safe alternativa)
-- =============================================================================
SELECT
  a.id AS stock_id_a,
  b.id AS stock_id_b,
  a.location_id,
  a.product_id,
  a.color_id,
  a.hardware_condition,
  a.current_stock AS stock_a,
  b.current_stock AS stock_b,
  a.sizes_data AS sizes_a,
  b.sizes_data AS sizes_b
FROM kiosco_stock a
JOIN kiosco_stock b
  ON a.location_id = b.location_id
 AND a.product_id = b.product_id
 AND a.color_id IS NOT DISTINCT FROM b.color_id
 AND a.hardware_condition = b.hardware_condition
 AND a.id < b.id
ORDER BY a.location_id, a.product_id, a.id;

-- =============================================================================
-- 3) ¿Existe el índice único null-safe?
-- =============================================================================
SELECT
  indexname,
  indexdef
FROM pg_indexes
WHERE tablename = 'kiosco_stock'
  AND indexname IN ('uq_kiosco_stock_loc_prod_color_hw', 'uq_kiosco_stock')
ORDER BY indexname;

-- =============================================================================
-- 4) Filas con color_id NULL (empaques / sin color) — volumen por kiosko
-- =============================================================================
SELECT
  ks.location_id,
  l.code AS location_code,
  COUNT(*) AS filas_null_color,
  SUM(COALESCE(ks.current_stock, 0)) AS stock_total
FROM kiosco_stock ks
LEFT JOIN locations l ON l.id = ks.location_id
WHERE ks.color_id IS NULL
GROUP BY ks.location_id, l.code
ORDER BY filas_null_color DESC;
