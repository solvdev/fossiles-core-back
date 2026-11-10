-- Completa product_variant_leather para productos (no cinchos) que ya tienen receta en algun color
-- pero les falta en otro color que hoy se produce. Sin receta el sistema no calcula el cuero de la
-- linea ("Sin receta de cuero") y no sale en pies2 por color.
--
-- Regla: el cuero es del color de la linea.
--   * cantidad por pieza = la que ya usa el producto en sus otros colores (la mas repetida; si hay
--     empate, la mayor).
--   * material = cuero del mismo color. Para CAFE y NEGRO se sigue lo que ya hace el producto:
--     si en CAFE/NEGRO usa CUERO DE CINCHO REVERSIBLE, se usa el reversible; si no, el LISO.
--   * acabados Flores/Espartaco: el cuero con ese acabado si existe en material; si no existe
--     (TOSTADO FLORES, WHISKY FLORES, WHISKY ESPARTACO) se usa el LISO del color base.
--   * COÑAC: no existia cuero coñac en `material`; se crea CUERO COÑAC LISO (M-CCO-L, copia de los
--     datos de CUERO CAFE LISO: unidad, proveedor, costo; sin existencias) y las recetas de coñac lo usan.
--     La receta AC-6 COÑAC que apuntaba a CUERO NEGRO LISO se corrige a ese cuero.
--   * NO se tocan: combinados (X/Y), GRIS, DUNA, FUSIA (no hay cuero equivalente claro),
--     ni productos sin ninguna receta (B-7-D). Esos se definen a mano.
-- Idempotente: no pisa recetas existentes (UNIQUE product_id, color_id).
-- Ejecutar en prod: psql -f scripts/fix-recetas-cuero-colores-faltantes.sql

BEGIN;

INSERT INTO material (sku, name, uom_id, cost, description, status, created_at, updated_at, max, min,
                      quantity, purchase_price, supplier_id, loss_percentage, manufacturing_uom_id,
                      purchase_quantity, purchase_uom_id, unit_cost, is_primary_leather)
SELECT 'M-CCO-L', 'CUERO COÑAC LISO', uom_id, cost, 'cuero para marroquineria conac liso', 'active', now(), now(),
       max, min, quantity, purchase_price, supplier_id, loss_percentage, manufacturing_uom_id,
       purchase_quantity, purchase_uom_id, unit_cost, is_primary_leather
FROM material WHERE sku = 'M-CC-L'
  AND NOT EXISTS (SELECT 1 FROM material WHERE sku = 'M-CCO-L');

UPDATE product_variant_leather v
SET leather_material_id = (SELECT id FROM material WHERE sku = 'M-CCO-L')
WHERE v.color_id = 5
  AND v.leather_material_id = (SELECT id FROM material WHERE sku = 'M-CN-L')
  AND v.product_id = (SELECT id FROM product WHERE code = 'AC-6');

WITH color_material(color_id, material_id) AS (
    VALUES
        (5, (SELECT id FROM material WHERE sku = 'M-CCO-L')),  -- COÑAC -> CUERO COÑAC LISO
        (1, 83),   -- WHISKY            -> CUERO WHISKY LISO
        (4, 82),   -- TOSTADO           -> CUERO TOSTADO LISO
        (6, 85),   -- AZUL              -> CUERO AZUL LISO
        (7, 86),   -- ROJO              -> CUERO ROJO LISO
        (8, 90),   -- BEIGE             -> CUERO BEIGE LISO
        (36, 99),  -- NEGRO ACABADO FLORES        -> CUERO NEGRO FLORES
        (35, 98),  -- CAFE MEDIO ACABADO FLORES   -> CUERO CAFE MEDIO FLORES
        (33, 102), -- NEGRO ACABADO ESPARTACO     -> CUERO NEGRO ESPARTACO
        (32, 101), -- CAFE MEDIO ACABADO ESPARTACO-> CUERO CAFE MEDIO ESPARTACO
        (20, 91),  -- AZUL ACABADO FLORES         -> CUERO AZUL FLORES
        (22, 92),  -- ROJO ACABADO FLORES         -> CUERO ROJO FLORES
        (28, 95),  -- ROSA GOLD ACABADO FLORES    -> CUERO GOLD ROSE FLORES
        (26, 94),  -- ROSA PRINCESA ACABADO FLORES-> CUERO ROSA PRINCESA FLORES
        (16, 82),  -- TOSTADO FLORES (sin cuero flores) -> TOSTADO LISO
        (42, 83),  -- WHISKY ACABADO FLORES (sin cuero flores) -> WHISKY LISO
        (41, 83)   -- WHISKY ACABADO ESPARTACO (sin cuero espartaco) -> WHISKY LISO
),
active_pairs AS (
    SELECT i.product_id, i.color_id
    FROM production_order_item i
    JOIN production_order po ON po.id = i.production_order_id
    WHERE po.status NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT')
    UNION
    SELECT ti.product_id, ti.color_id
    FROM task_item ti JOIN task t ON t.id = ti.task_id
    WHERE t.status IN ('PENDING', 'IN_PROGRESS')
),
-- CAFE (2) y NEGRO (3) se resuelven aparte porque dependen de lo que ya use el producto.
needed AS (
    SELECT DISTINCT a.product_id, a.color_id
    FROM active_pairs a
    JOIN product p ON p.id = a.product_id
    WHERE a.color_id IS NOT NULL
      AND (a.color_id IN (2, 3) OR a.color_id IN (SELECT color_id FROM color_material))
      AND (p.cincho_type IS NULL OR p.cincho_type = '')
      AND p.name NOT ILIKE '%cincho%'
      AND NOT EXISTS (SELECT 1 FROM product_variant_leather v
                      WHERE v.product_id = a.product_id AND (v.color_id = a.color_id OR v.color_id IS NULL))
      AND EXISTS (SELECT 1 FROM product_variant_leather v WHERE v.product_id = a.product_id)
),
qty AS (
    SELECT DISTINCT ON (product_id) product_id, qty_per_unit
    FROM (SELECT product_id, qty_per_unit, count(*) n
          FROM product_variant_leather WHERE color_id IS NOT NULL
          GROUP BY product_id, qty_per_unit) s
    ORDER BY product_id, n DESC, qty_per_unit DESC
),
uses_reversible AS (
    SELECT DISTINCT product_id FROM product_variant_leather
    WHERE leather_material_id IN (74, 75) AND color_id IN (2, 3)
)
INSERT INTO product_variant_leather (product_id, color_id, leather_material_id, qty_per_unit, created_at)
SELECT n.product_id, n.color_id,
       CASE
           WHEN n.color_id = 2 THEN CASE WHEN ur.product_id IS NOT NULL THEN 75 ELSE 81 END
           WHEN n.color_id = 3 THEN CASE WHEN ur.product_id IS NOT NULL THEN 74 ELSE 80 END
           ELSE cm.material_id
       END,
       q.qty_per_unit, now()
FROM needed n
JOIN qty q ON q.product_id = n.product_id
LEFT JOIN color_material cm ON cm.color_id = n.color_id
LEFT JOIN uses_reversible ur ON ur.product_id = n.product_id
ON CONFLICT (product_id, color_id) DO NOTHING;

COMMIT;
