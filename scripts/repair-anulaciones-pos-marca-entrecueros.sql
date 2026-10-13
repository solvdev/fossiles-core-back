-- ---------------------------------------------------------------------------
-- Entrecueros: anulaciones de venta POS que devolvieron la unidad al stock equivocado.
--
-- Problema: al vender, el POS descuenta del stock de la marca (NAUTICA, LEVIS, SINTETICO:LEVIS, NINO, DAMA...).
-- Al ANULAR la venta, la unidad regresaba a la fila generica NUEVO. Resultado: el stock de la marca queda
-- corto, aparece un stock "NUEVO" fantasma y en el conteo fisico sale una linea sin marca con "Anul. venta" y
-- faltante, mas un sobrante en la linea de la marca.
-- (El codigo ya se corrigio: la anulacion regresa al stock del que salio la venta.)
--
-- Que hace este script (solo ventas POS ya ANULADAS):
--   1) Busca ANULACION cuya fila de stock NO es la misma de la VENTA de esa linea (mismo producto, color,
--      talla y venta). Solo corrige cuando la venta salio de UN solo stock (si salio de varios, es ambiguo y
--      se deja para revision manual).
--   2) Mueve el movimiento ANULACION al stock correcto (el kardex y el conteo se calculan por movimientos).
--   3) Ajusta kiosco_stock del stock origen (resta) y del destino (suma): current_stock y sizes_data.
--
-- Uso: correr primero el PASO 1 (solo lectura) y revisar. Despues correr el PASO 2 completo.
-- Es idempotente: si se vuelve a correr, ya no encuentra nada que mover.
-- Despues de correr, recargar el borrador del conteo fisico (se recalcula solo al abrirlo).
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- PASO 1: vista previa (solo lectura)
-- ===========================================================================
SELECT ks.sale_number,
       ks.status AS estado_venta,
       p.code AS producto,
       c.name AS color,
       NULLIF(btrim(a.size_key), '') AS talla,
       a.quantity AS cantidad,
       sa.hardware_condition AS stock_donde_cayo_la_anulacion,
       sv.hardware_condition AS stock_de_la_venta,
       a.id AS movimiento_anulacion
FROM kiosco_movement a
JOIN kiosco_stock sa ON sa.id = a.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = a.reference_id AND ks.kiosk_location_id = sa.location_id
JOIN product p ON p.id = sa.product_id
LEFT JOIN colors c ON c.id = sa.color_id
JOIN LATERAL (
    SELECT MIN(m.kiosco_stock_id) AS kiosco_stock_id,
           COUNT(DISTINCT m.kiosco_stock_id) AS stocks
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    WHERE m.movement_type = 'VENTA'
      AND m.reference_id = a.reference_id
      AND s.location_id = sa.location_id
      AND s.product_id = sa.product_id
      AND s.color_id IS NOT DISTINCT FROM sa.color_id
      AND COALESCE(btrim(m.size_key), '') = COALESCE(btrim(a.size_key), '')
) v ON v.stocks = 1
JOIN kiosco_stock sv ON sv.id = v.kiosco_stock_id
WHERE a.movement_type = 'ANULACION'
  AND ks.status = 'VOID'
  AND v.kiosco_stock_id <> a.kiosco_stock_id
ORDER BY ks.sale_number, p.code;

-- Aparte (solo informativo, no se corrige solo): ventas anuladas donde la VENTA salio de varios stocks o
-- donde se anulo menos de lo vendido (p. ej. dos lineas iguales en la misma venta; el codigo viejo solo
-- devolvia la primera).
SELECT ks.sale_number,
       p.code AS producto,
       c.name AS color,
       NULLIF(btrim(v.size_key), '') AS talla,
       v.vendido,
       COALESCE(an.anulado, 0) AS anulado
FROM kiosk_sale ks
JOIN LATERAL (
    SELECT s.product_id, s.color_id, s.location_id, COALESCE(btrim(m.size_key), '') AS size_key,
           SUM(m.quantity) AS vendido
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    WHERE m.movement_type = 'VENTA' AND m.reference_id = ks.id AND s.location_id = ks.kiosk_location_id
    GROUP BY s.product_id, s.color_id, s.location_id, COALESCE(btrim(m.size_key), '')
) v ON TRUE
LEFT JOIN LATERAL (
    SELECT SUM(m.quantity) AS anulado
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    WHERE m.movement_type = 'ANULACION' AND m.reference_id = ks.id
      AND s.location_id = v.location_id AND s.product_id = v.product_id
      AND s.color_id IS NOT DISTINCT FROM v.color_id
      AND COALESCE(btrim(m.size_key), '') = v.size_key
) an ON TRUE
JOIN product p ON p.id = v.product_id
LEFT JOIN colors c ON c.id = v.color_id
WHERE ks.status = 'VOID'
  AND COALESCE(an.anulado, 0) < v.vendido
ORDER BY ks.sale_number, p.code;

-- ===========================================================================
-- PASO 2: correccion
-- ===========================================================================
BEGIN;

CREATE TEMP TABLE tmp_anulacion_fix ON COMMIT DROP AS
SELECT a.id AS movement_id,
       a.kiosco_stock_id AS from_stock_id,
       v.kiosco_stock_id AS to_stock_id,
       a.quantity,
       NULLIF(btrim(a.size_key), '') AS size_key,
       COALESCE(a.affects_stock, TRUE) AS affects_stock
FROM kiosco_movement a
JOIN kiosco_stock sa ON sa.id = a.kiosco_stock_id
JOIN kiosk_sale ks ON ks.id = a.reference_id AND ks.kiosk_location_id = sa.location_id
JOIN LATERAL (
    SELECT MIN(m.kiosco_stock_id) AS kiosco_stock_id,
           COUNT(DISTINCT m.kiosco_stock_id) AS stocks
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    WHERE m.movement_type = 'VENTA'
      AND m.reference_id = a.reference_id
      AND s.location_id = sa.location_id
      AND s.product_id = sa.product_id
      AND s.color_id IS NOT DISTINCT FROM sa.color_id
      AND COALESCE(btrim(m.size_key), '') = COALESCE(btrim(a.size_key), '')
) v ON v.stocks = 1
WHERE a.movement_type = 'ANULACION'
  AND ks.status = 'VOID'
  AND v.kiosco_stock_id <> a.kiosco_stock_id;

-- Cambio neto por stock y talla (solo movimientos que afectan stock).
CREATE TEMP TABLE tmp_stock_delta ON COMMIT DROP AS
SELECT stock_id, size_key, SUM(qty) AS delta
FROM (
    SELECT from_stock_id AS stock_id, size_key, -quantity AS qty FROM tmp_anulacion_fix WHERE affects_stock
    UNION ALL
    SELECT to_stock_id AS stock_id, size_key, quantity AS qty FROM tmp_anulacion_fix WHERE affects_stock
) x
GROUP BY stock_id, size_key;

-- 2a) Mover el movimiento ANULACION al stock de la venta.
UPDATE kiosco_movement m
SET kiosco_stock_id = f.to_stock_id
FROM tmp_anulacion_fix f
WHERE m.id = f.movement_id;

-- 2b) Tallas: stocks con desglose por talla (sizes_data con contenido).
UPDATE kiosco_stock s
SET sizes_data = (
        SELECT COALESCE(jsonb_object_agg(t.k, t.v), '{}'::jsonb)::text
        FROM (
            SELECT u.k, SUM(u.v) AS v
            FROM (
                SELECT e.key AS k, e.value::numeric AS v
                FROM jsonb_each_text(s.sizes_data::jsonb) e
                UNION ALL
                SELECT d.size_key AS k, d.delta AS v
                FROM tmp_stock_delta d
                WHERE d.stock_id = s.id AND d.size_key IS NOT NULL
            ) u
            GROUP BY u.k
            HAVING SUM(u.v) > 0
        ) t
    ),
    updated_at = NOW(),
    last_updated_at = NOW()
WHERE s.id IN (SELECT stock_id FROM tmp_stock_delta WHERE size_key IS NOT NULL)
  AND s.sizes_data IS NOT NULL
  AND btrim(s.sizes_data) NOT IN ('', '{}');

-- 2c) Stock destino sin desglose y en 0: registrar la talla recuperada.
UPDATE kiosco_stock s
SET sizes_data = (
        SELECT jsonb_object_agg(d.size_key, d.delta)::text
        FROM tmp_stock_delta d
        WHERE d.stock_id = s.id AND d.size_key IS NOT NULL AND d.delta > 0
    ),
    updated_at = NOW(),
    last_updated_at = NOW()
WHERE s.id IN (SELECT stock_id FROM tmp_stock_delta WHERE size_key IS NOT NULL AND delta > 0)
  AND (s.sizes_data IS NULL OR btrim(s.sizes_data) IN ('', '{}'))
  AND COALESCE(s.current_stock, 0) = 0;

-- 2d) current_stock: suma de tallas si hay desglose; si no, el stock anterior mas el cambio neto.
UPDATE kiosco_stock s
SET current_stock = CASE
        WHEN s.sizes_data IS NOT NULL AND btrim(s.sizes_data) NOT IN ('', '{}')
            THEN (SELECT COALESCE(SUM(e.value::numeric), 0)::int FROM jsonb_each_text(s.sizes_data::jsonb) e)
        ELSE GREATEST(0, COALESCE(s.current_stock, 0)
                          + (SELECT SUM(d.delta)::int FROM tmp_stock_delta d WHERE d.stock_id = s.id))
    END,
    updated_at = NOW(),
    last_updated_at = NOW()
WHERE s.id IN (SELECT stock_id FROM tmp_stock_delta);

-- Verificacion: stocks afectados despues del ajuste
SELECT s.id, p.code AS producto, c.name AS color, s.hardware_condition, s.current_stock, s.sizes_data
FROM kiosco_stock s
JOIN product p ON p.id = s.product_id
LEFT JOIN colors c ON c.id = s.color_id
WHERE s.id IN (SELECT stock_id FROM tmp_stock_delta)
ORDER BY p.code, c.name, s.hardware_condition;

-- Si algo no cuadra, ejecutar ROLLBACK; en lugar de COMMIT;
COMMIT;
