-- Repara movimientos de inventario inicial que se crearon como AJUSTE (sin size_key en cinchos).
-- Efecto deseado:
--   - Productos sin tallas: AJUSTE -> ENTRADA (quantity > 0)
--   - Cinchos: borra AJUSTE agregado y crea ENTRADA por talla desde sizes_data / opening items
--   - Elimina AJUSTE con quantity = 0 (no aportan unidades)
--
-- IMPORTANTE:
--   1) Reemplaza :location_id (ej. 15)
--   2) Revisa el SELECT de diagnóstico antes del UPDATE/DELETE/INSERT
--   3) Ejecutar en transacción; commit solo si el conteo cuadra
--   4) No cambia current_stock: solo corrige el ledger para el conteo físico

BEGIN;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

-- =============================================================================
-- DIAGNÓSTICO
-- =============================================================================
SELECT
    km.id,
    km.kiosco_stock_id,
    p.code,
    p.name,
    km.movement_type,
    km.quantity,
    km.size_key,
    km.stock_before,
    km.stock_after,
    km.reason,
    ks.sizes_data,
    ks.current_stock
FROM kiosco_movement km
JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
JOIN product p ON p.id = ks.product_id
WHERE ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
  AND km.movement_type = 'AJUSTE'
ORDER BY p.code, ks.color_id, km.id;

-- =============================================================================
-- 1) Productos SIN desglose de tallas: AJUSTE -> ENTRADA
-- =============================================================================
UPDATE kiosco_movement km
SET movement_type = 'ENTRADA'
FROM kiosco_stock ks
WHERE km.kiosco_stock_id = ks.id
  AND ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
  AND km.movement_type = 'AJUSTE'
  AND km.quantity > 0
  AND (km.size_key IS NULL OR BTRIM(km.size_key) = '')
  AND (
        ks.sizes_data IS NULL
        OR BTRIM(ks.sizes_data) = ''
        OR BTRIM(ks.sizes_data) IN ('{}', 'null')
      );

-- =============================================================================
-- 2) Cinchos quantity = 0
--    a) Sin movimientos previos: recrear como ENTRADA por talla (0 -> sizes)
--    b) Con historial previo: solo borrar el AJUSTE vacío (no aporta delta)
-- =============================================================================

CREATE TEMP TABLE tmp_opening_zero_cincho ON COMMIT DROP AS
SELECT
    km.id AS old_movement_id,
    km.kiosco_stock_id,
    km.user_id,
    km.reason,
    km.created_at,
    km.affects_stock,
    ks.sizes_data,
    km.stock_after AS target_total,
    NOT EXISTS (
        SELECT 1
        FROM kiosco_movement prev
        WHERE prev.kiosco_stock_id = km.kiosco_stock_id
          AND prev.id <> km.id
          AND COALESCE(prev.affects_stock, TRUE) = TRUE
          AND (
                prev.created_at < km.created_at
                OR (prev.created_at = km.created_at AND prev.id < km.id)
              )
    ) AS is_first_ledger_row
FROM kiosco_movement km
JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
WHERE ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
  AND km.movement_type = 'AJUSTE'
  AND km.quantity = 0
  AND ks.sizes_data IS NOT NULL
  AND BTRIM(ks.sizes_data) <> ''
  AND BTRIM(ks.sizes_data) NOT IN ('{}', 'null');

CREATE TEMP TABLE tmp_opening_zero_entrada_sizes ON COMMIT DROP AS
SELECT
    t.kiosco_stock_id,
    t.user_id,
    t.reason,
    t.created_at,
    t.affects_stock,
    kv.key AS size_key,
    (kv.value)::int AS quantity,
    row_number() OVER (
        PARTITION BY t.kiosco_stock_id
        ORDER BY kv.key
    ) AS size_ord
FROM tmp_opening_zero_cincho t
CROSS JOIN LATERAL jsonb_each_text(t.sizes_data::jsonb) AS kv
WHERE t.is_first_ledger_row
  AND (kv.value)::int > 0;

DELETE FROM kiosco_movement km
USING tmp_opening_zero_cincho t
WHERE km.id = t.old_movement_id;

INSERT INTO kiosco_movement (
    kiosco_stock_id,
    movement_type,
    quantity,
    size_key,
    stock_before,
    stock_after,
    reference_id,
    reason,
    affects_stock,
    user_id,
    origin_location_id,
    destination_location_id,
    created_at
)
SELECT
    s.kiosco_stock_id,
    'ENTRADA',
    s.quantity,
    s.size_key,
    COALESCE(SUM(s.quantity) OVER (
        PARTITION BY s.kiosco_stock_id
        ORDER BY s.size_ord
        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
    ), 0) AS stock_before,
    SUM(s.quantity) OVER (
        PARTITION BY s.kiosco_stock_id
        ORDER BY s.size_ord
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS stock_after,
    NULL,
    s.reason,
    s.affects_stock,
    s.user_id,
    NULL,
    NULL,
    s.created_at + (s.size_ord || ' milliseconds')::interval
FROM tmp_opening_zero_entrada_sizes s;

-- Cualquier AJUSTE qty=0 restante (sin sizes / no recreable): borrar
DELETE FROM kiosco_movement km
USING kiosco_stock ks
WHERE km.kiosco_stock_id = ks.id
  AND ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
  AND km.movement_type = 'AJUSTE'
  AND km.quantity = 0;

-- =============================================================================
-- 3) Cinchos con AJUSTE agregado (quantity > 0, sin size_key):
--    reemplazar por ENTRADA por talla desde sizes_data del stock.
--    stock_before/after del ledger se reconstruyen de forma acumulativa por stock.
-- =============================================================================

-- 3a) Guardar metadatos del AJUSTE a reemplazar
CREATE TEMP TABLE tmp_opening_ajuste_cincho ON COMMIT DROP AS
SELECT
    km.id AS old_movement_id,
    km.kiosco_stock_id,
    km.user_id,
    km.reason,
    km.created_at,
    km.affects_stock,
    ks.sizes_data,
    km.stock_before AS opening_before
FROM kiosco_movement km
JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
WHERE ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
  AND km.movement_type = 'AJUSTE'
  AND km.quantity > 0
  AND (km.size_key IS NULL OR BTRIM(km.size_key) = '')
  AND ks.sizes_data IS NOT NULL
  AND BTRIM(ks.sizes_data) <> ''
  AND BTRIM(ks.sizes_data) NOT IN ('{}', 'null');

-- 3b) Expandir tallas con qty > 0
CREATE TEMP TABLE tmp_opening_entrada_sizes ON COMMIT DROP AS
SELECT
    t.kiosco_stock_id,
    t.user_id,
    t.reason,
    t.created_at,
    t.affects_stock,
    t.opening_before,
    kv.key AS size_key,
    (kv.value)::int AS quantity,
    row_number() OVER (
        PARTITION BY t.kiosco_stock_id
        ORDER BY kv.key
    ) AS size_ord
FROM tmp_opening_ajuste_cincho t
CROSS JOIN LATERAL jsonb_each_text(t.sizes_data::jsonb) AS kv
WHERE (kv.value)::int > 0;

-- 3c) Borrar AJUSTEs agregados de cincho
DELETE FROM kiosco_movement km
USING tmp_opening_ajuste_cincho t
WHERE km.id = t.old_movement_id;

-- 3d) Insertar ENTRADAs por talla (cadena stock_before/after)
INSERT INTO kiosco_movement (
    kiosco_stock_id,
    movement_type,
    quantity,
    size_key,
    stock_before,
    stock_after,
    reference_id,
    reason,
    affects_stock,
    user_id,
    origin_location_id,
    destination_location_id,
    created_at
)
SELECT
    s.kiosco_stock_id,
    'ENTRADA',
    s.quantity,
    s.size_key,
    s.opening_before + COALESCE(SUM(s.quantity) OVER (
        PARTITION BY s.kiosco_stock_id
        ORDER BY s.size_ord
        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
    ), 0) AS stock_before,
    s.opening_before + SUM(s.quantity) OVER (
        PARTITION BY s.kiosco_stock_id
        ORDER BY s.size_ord
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS stock_after,
    NULL,
    s.reason,
    s.affects_stock,
    s.user_id,
    NULL,
    NULL,
    s.created_at + (s.size_ord || ' milliseconds')::interval
FROM tmp_opening_entrada_sizes s;

-- =============================================================================
-- VERIFICACIÓN
-- =============================================================================
SELECT
    km.id,
    p.code,
    km.movement_type,
    km.quantity,
    km.size_key,
    km.stock_before,
    km.stock_after,
    km.reason
FROM kiosco_movement km
JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
JOIN product p ON p.id = ks.product_id
WHERE ks.location_id = :location_id
  AND km.reason LIKE 'Inventario inicial - migración%'
ORDER BY p.code, ks.color_id, km.created_at, km.id;

-- Si todo OK:
--   COMMIT;
-- Si no:
--   ROLLBACK;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);
