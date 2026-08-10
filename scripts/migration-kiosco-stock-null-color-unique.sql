-- Empaques y productos sin color usan color_id NULL.
-- En PostgreSQL, UNIQUE (..., color_id, ...) permite varias filas con NULL
-- (NULL IS DISTINCT FROM NULL en índices UNIQUE clásicos).
-- Este script limpia duplicados y deja un índice único null-safe con COALESCE.
--
-- Nota: la consolidación SQL suma current_stock; el merge fino de sizes_data
-- lo hace KioscoStockProvisioningService.collapseDuplicates en runtime.
-- Tras migrar, ejecutar scripts/audit-kiosco-stock-duplicates.sql.

-- 1) Detectar duplicados (incluye color NULL; misma clave lógica que el índice)
SELECT
  location_id,
  product_id,
  COALESCE(color_id, -1) AS color_key,
  color_id,
  hardware_condition,
  COUNT(*) AS filas,
  ARRAY_AGG(id ORDER BY id) AS stock_ids,
  SUM(COALESCE(current_stock, 0)) AS stock_total
FROM kiosco_stock
GROUP BY location_id, product_id, COALESCE(color_id, -1), color_id, hardware_condition
HAVING COUNT(*) > 1
ORDER BY filas DESC, location_id, product_id;

-- Equivalente con IS NOT DISTINCT FROM (misma semántica de igualdad null-safe):
-- SELECT a.id, b.id, a.location_id, a.product_id, a.color_id, a.hardware_condition
-- FROM kiosco_stock a
-- JOIN kiosco_stock b
--   ON a.location_id = b.location_id
--  AND a.product_id = b.product_id
--  AND a.color_id IS NOT DISTINCT FROM b.color_id
--  AND a.hardware_condition = b.hardware_condition
--  AND a.id < b.id;

-- 2) Consolidar: movimientos → fila más antigua; sumar stock; borrar extras
WITH dups AS (
  SELECT
    location_id,
    product_id,
    COALESCE(color_id, -1) AS color_key,
    hardware_condition,
    MIN(id) AS keep_id,
    ARRAY_AGG(id ORDER BY id) AS ids,
    SUM(COALESCE(current_stock, 0)) AS total_stock,
    MAX(COALESCE(minimum_stock, 0)) AS max_minimum
  FROM kiosco_stock
  GROUP BY location_id, product_id, COALESCE(color_id, -1), hardware_condition
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
  GROUP BY location_id, product_id, COALESCE(color_id, -1), hardware_condition
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
  GROUP BY location_id, product_id, COALESCE(color_id, -1), hardware_condition
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
-- COALESCE(-1) evita colisión de NULLs; si existiera color_id = -1 real, usar
-- en PG 15+: UNIQUE NULLS NOT DISTINCT (location_id, product_id, color_id, hardware_condition).
ALTER TABLE kiosco_stock DROP CONSTRAINT IF EXISTS uq_kiosco_stock;

DROP INDEX IF EXISTS uq_kiosco_stock_loc_prod_color_hw;

CREATE UNIQUE INDEX uq_kiosco_stock_loc_prod_color_hw
  ON kiosco_stock (location_id, product_id, COALESCE(color_id, -1), hardware_condition);
