-- Empaques y productos sin color usan color_id NULL.
-- En PostgreSQL, UNIQUE (..., color_id, ...) permite varias filas con NULL.
-- Este script limpia duplicados y deja un índice único con COALESCE.

-- 1) Detectar duplicados (incluye color NULL)
SELECT
  location_id,
  product_id,
  color_id,
  hardware_condition,
  COUNT(*) AS filas,
  ARRAY_AGG(id ORDER BY id) AS stock_ids,
  SUM(current_stock) AS stock_total
FROM kiosco_stock
GROUP BY location_id, product_id, color_id, hardware_condition
HAVING COUNT(*) > 1
ORDER BY filas DESC, location_id, product_id;

-- 2) Consolidar: movimientos → fila más antigua; sumar stock; borrar extras
WITH dups AS (
  SELECT
    location_id,
    product_id,
    color_id,
    hardware_condition,
    MIN(id) AS keep_id,
    ARRAY_AGG(id ORDER BY id) AS ids,
    SUM(COALESCE(current_stock, 0)) AS total_stock,
    MAX(COALESCE(minimum_stock, 0)) AS max_minimum
  FROM kiosco_stock
  GROUP BY location_id, product_id, color_id, hardware_condition
  HAVING COUNT(*) > 1
),
to_delete AS (
  SELECT d.keep_id, d.total_stock, d.max_minimum, unnest(d.ids[2:]) AS drop_id
  FROM dups d
)
UPDATE kiosco_movement m
SET kiosco_stock_id = td.keep_id
FROM to_delete td
WHERE m.kiosco_stock_id = td.drop_id;

WITH dups AS (
  SELECT
    MIN(id) AS keep_id,
    SUM(COALESCE(current_stock, 0)) AS total_stock,
    MAX(COALESCE(minimum_stock, 0)) AS max_minimum
  FROM kiosco_stock
  GROUP BY location_id, product_id, color_id, hardware_condition
  HAVING COUNT(*) > 1
)
UPDATE kiosco_stock ks
SET current_stock = d.total_stock,
    minimum_stock = d.max_minimum,
    updated_at = NOW(),
    last_updated_at = NOW()
FROM dups d
WHERE ks.id = d.keep_id;

WITH dups AS (
  SELECT ARRAY_AGG(id ORDER BY id) AS ids
  FROM kiosco_stock
  GROUP BY location_id, product_id, color_id, hardware_condition
  HAVING COUNT(*) > 1
),
to_delete AS (
  SELECT unnest(ids[2:]) AS drop_id
  FROM dups
)
DELETE FROM kiosco_stock ks
USING to_delete td
WHERE ks.id = td.drop_id;

-- 3) Índice único que sí cubre color_id NULL
ALTER TABLE kiosco_stock DROP CONSTRAINT IF EXISTS uq_kiosco_stock;

DROP INDEX IF EXISTS uq_kiosco_stock_loc_prod_color_hw;

CREATE UNIQUE INDEX uq_kiosco_stock_loc_prod_color_hw
  ON kiosco_stock (location_id, product_id, COALESCE(color_id, -1), hardware_condition);
