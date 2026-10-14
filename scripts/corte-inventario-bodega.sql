-- =============================================================================
-- CORTE DE INVENTARIO DE BODEGAS (producto terminado) — PostgreSQL
-- =============================================================================
-- Deja las bodegas indicadas exactamente como el Excel de respaldo:
--   1) Respalda las filas actuales.
--   2) Lleva cada fila a 0 con un movimiento CORTE_INVENTARIO que anula toda la historia del
--      kardex de esa fila (así un recálculo del kardex no revive saldos viejos).
--   3) Carga lo del Excel con movimientos CARGA_INICIAL (uno por talla en cinchos).
-- No toca kioskos (kiosco_stock) ni la ubicación EN_TRANSITO.
--
-- Uso (DBeaver o psql, en UNA misma conexión/sesión, en orden):
--   PASO 0  Ajustar parámetros (fecha, referencia y ubicaciones).
--   PASO 1  Revisiones previas: deben dar 0 filas o estar explicadas.
--   PASO 2  Crear la tabla de carga e importar el Excel (guardado como CSV).
--   PASO 3  Validaciones del Excel: TODAS deben dar 0 filas.
--   PASO 4  Aplicar (una sola transacción).
--   PASO 5  Verificar.
-- Plantilla del CSV: scripts/corte-inventario-plantilla.csv
-- =============================================================================


-- =============================================================================
-- PASO 0. PARÁMETROS
-- =============================================================================
DROP TABLE IF EXISTS corte_param;
CREATE TABLE corte_param AS
SELECT TIMESTAMP '2026-10-20 18:00:00' AS fecha,        -- <== fecha/hora del corte
       'CORTE-20261020'::varchar(100)  AS referencia;    -- <== referencia que verá el kardex

-- Ubicaciones que se cortan (códigos de la tabla locations). Revise con:
--   SELECT l.id, l.code, l.name, l.categoria, SUM(pil.quantity) AS unidades
--   FROM locations l LEFT JOIN product_inventory_location pil ON pil.location_id = l.id
--   GROUP BY l.id ORDER BY unidades DESC NULLS LAST;
DROP TABLE IF EXISTS corte_ubicacion;
CREATE TABLE corte_ubicacion (code varchar(15) PRIMARY KEY);
INSERT INTO corte_ubicacion (code) VALUES
    ('BODEGA_PT'),
    ('BODEGA_DEV'),       -- <== cambie por el código real de Devoluciones si es otro
    ('DEV_NO_APTA');


-- =============================================================================
-- PASO 1. REVISIONES PREVIAS
-- =============================================================================
-- 1a. Las ubicaciones existen (DEV_NO_APTA puede no existir aún: se crea en el PASO 4)
SELECT u.code AS ubicacion_no_existe
FROM corte_ubicacion u
LEFT JOIN locations l ON l.code = u.code
WHERE l.id IS NULL AND u.code <> 'DEV_NO_APTA';

-- 1b. Envíos en camino (SENT): recíbalos antes del corte o confirme que el Excel NO
--     cuenta esas unidades (ya salieron de bodega).
SELECT s.shipment_number, s.status, s.sent_at, l.name AS destino
FROM product_shipment s LEFT JOIN locations l ON l.id = s.location_id
WHERE s.status = 'SENT';

-- 1c. Ventas en línea preparadas que aún no se despachan: el sistema ya las descontó.
--     Si físicamente siguen en bodega, NO deben ir en el Excel (o despáchelas antes).
SELECT os.sale_number, os.status, os.customer_name
FROM online_sale os
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'DEVOLUCION', 'ANULADA')
  AND COALESCE((SELECT SUM(k.quantity) FROM product_inventory_kardex k
                WHERE k.reference_id = os.id AND k.reference_type = 'ONLINE_SALE_PREPARE'), 0) < 0;


-- =============================================================================
-- PASO 2. TABLA DE CARGA
-- =============================================================================
-- Guarde el Excel como CSV con columnas: ubicacion,codigo,color,talla,cantidad
--   - ubicacion: código de la tabla locations (BODEGA_PT, BODEGA_DEV, DEV_NO_APTA)
--   - color: nombre del color tal como está en el sistema (vacío si el producto no maneja color)
--   - talla: obligatoria en cinchos; vacía en el resto
-- Importe el CSV a esta tabla (DBeaver: clic derecho en la tabla > Importar datos), o en psql:
--   \copy corte_carga(ubicacion,codigo,color,talla,cantidad) FROM 'ruta/archivo.csv' CSV HEADER
DROP TABLE IF EXISTS corte_carga;
CREATE TABLE corte_carga (
    fila      serial PRIMARY KEY,
    ubicacion text,
    codigo    text,
    color     text,
    talla     text,
    cantidad  numeric(12, 3)
);

-- Vista con todo resuelto (ids, si es cincho). Se usa en validaciones y en la aplicación.
CREATE OR REPLACE VIEW corte_carga_resuelta AS
SELECT c.fila,
       UPPER(TRIM(c.ubicacion))                      AS ubicacion,
       UPPER(TRIM(c.codigo))                         AS codigo,
       NULLIF(TRIM(c.color), '')                     AS color,
       NULLIF(UPPER(TRIM(c.talla)), '')              AS talla,
       c.cantidad,
       l.id                                          AS location_id,
       p.id                                          AS product_id,
       p.name                                        AS product_name,
       co.id                                         AS color_id,
       (p.code NOT ILIKE 'SUM-%'
        AND (p.code ILIKE 'FOSS%' OR LOWER(p.name) LIKE '%cincho%'))    AS por_talla,
       (p.code NOT ILIKE 'SUM-%'
        AND (p.code ILIKE 'FOSS%' OR LOWER(p.name) LIKE '%cincho%'
             OR COALESCE(p.cincho_type, '') <> ''))                     AS es_cincho
FROM corte_carga c
LEFT JOIN locations l ON l.code = UPPER(TRIM(c.ubicacion))
LEFT JOIN product p   ON UPPER(p.code) = UPPER(TRIM(c.codigo))
LEFT JOIN colors co   ON NULLIF(TRIM(c.color), '') IS NOT NULL
                     AND UPPER(TRIM(co.name)) = UPPER(TRIM(c.color));


-- =============================================================================
-- PASO 3. VALIDACIONES DEL EXCEL (todas deben dar 0 filas)
-- =============================================================================
-- 3a. Ubicación que no está en la lista del corte
SELECT fila, ubicacion FROM corte_carga_resuelta
WHERE ubicacion NOT IN (SELECT code FROM corte_ubicacion);

-- 3b. Código de producto que no existe
SELECT fila, codigo FROM corte_carga_resuelta WHERE product_id IS NULL;

-- 3c. Color escrito que no existe (o repetido con otro nombre)
SELECT fila, codigo, color FROM corte_carga_resuelta WHERE color IS NOT NULL AND color_id IS NULL;

-- 3d. Cincho sin talla
SELECT fila, codigo, color FROM corte_carga_resuelta WHERE es_cincho AND talla IS NULL;

-- 3e. Talla en un producto que no es cincho
SELECT fila, codigo, talla FROM corte_carga_resuelta WHERE NOT es_cincho AND talla IS NOT NULL;

-- 3f. Cantidad vacía, negativa o con decimales
SELECT fila, codigo, cantidad FROM corte_carga_resuelta
WHERE cantidad IS NULL OR cantidad < 0 OR cantidad <> TRUNC(cantidad);

-- 3g. Filas repetidas (misma ubicación + producto + color + talla). No es error: se suman.
SELECT ubicacion, codigo, color, talla, COUNT(*) AS veces, SUM(cantidad) AS total
FROM corte_carga_resuelta
GROUP BY ubicacion, codigo, color, talla HAVING COUNT(*) > 1;

-- 3h. Resumen de lo que se va a cargar (compárelo con los totales del Excel)
SELECT ubicacion, COUNT(*) AS filas, SUM(cantidad) AS unidades
FROM corte_carga_resuelta GROUP BY ubicacion ORDER BY ubicacion;


-- =============================================================================
-- PASO 4. APLICAR (todo o nada)
-- =============================================================================
BEGIN;

-- 4.0 Crear DEV_NO_APTA si se usa y aún no existe
INSERT INTO locations (code, name, categoria)
SELECT 'DEV_NO_APTA', 'Devoluciones no aptas', 'DEVOLUCION_NO_APTA'
WHERE EXISTS (SELECT 1 FROM corte_ubicacion WHERE code = 'DEV_NO_APTA')
  AND NOT EXISTS (SELECT 1 FROM locations WHERE code = 'DEV_NO_APTA');

-- 4.1 Respaldos (se pueden borrar cuando el corte esté verificado)
DROP TABLE IF EXISTS corte_bk_inventario;
CREATE TABLE corte_bk_inventario AS
SELECT pil.* FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
WHERE l.code IN (SELECT code FROM corte_ubicacion);

DROP TABLE IF EXISTS corte_bk_fifo;
CREATE TABLE corte_bk_fifo AS
SELECT b.* FROM product_fifo_batch b
JOIN locations l ON l.id = b.location_id
WHERE l.code IN (SELECT code FROM corte_ubicacion) AND b.quantity_available <> 0;

-- 4.2 Movimiento CORTE_INVENTARIO: anula la suma del kardex de cada fila (por talla)
INSERT INTO product_inventory_kardex
    (product_id, location_id, color_id, size_label, movement_type, quantity, quantity_before,
     quantity_after, reference_type, reference_number, description, movement_date, created_at)
SELECT k.product_id, k.location_id, k.color_id, k.size_label, 'CORTE_INVENTARIO',
       -SUM(k.quantity),
       COALESCE(MAX(pil.quantity), 0),
       0,
       'CORTE_INVENTARIO', (SELECT referencia FROM corte_param),
       'Corte de inventario: se lleva a 0 antes de cargar el respaldo (saldo en sistema '
           || COALESCE(MAX(pil.quantity), 0) || ')',
       (SELECT fecha FROM corte_param), NOW()
FROM product_inventory_kardex k
JOIN locations l ON l.id = k.location_id
LEFT JOIN product_inventory_location pil
       ON pil.product_id = k.product_id AND pil.location_id = k.location_id
      AND pil.color_id IS NOT DISTINCT FROM k.color_id
WHERE l.code IN (SELECT code FROM corte_ubicacion)
GROUP BY k.product_id, k.location_id, k.color_id, k.size_label
HAVING SUM(k.quantity) <> 0;

-- Filas con stock cuyo kardex ya sumaba 0 (o no tenía movimientos): también dejan constancia
INSERT INTO product_inventory_kardex
    (product_id, location_id, color_id, movement_type, quantity, quantity_before, quantity_after,
     reference_type, reference_number, description, movement_date, created_at)
SELECT pil.product_id, pil.location_id, pil.color_id, 'CORTE_INVENTARIO', 0, pil.quantity, 0,
       'CORTE_INVENTARIO', (SELECT referencia FROM corte_param),
       'Corte de inventario: se lleva a 0 (saldo en sistema ' || pil.quantity || ')',
       (SELECT fecha FROM corte_param), NOW()
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
WHERE l.code IN (SELECT code FROM corte_ubicacion)
  AND pil.quantity <> 0
  AND NOT EXISTS (SELECT 1 FROM product_inventory_kardex k
                  WHERE k.product_id = pil.product_id AND k.location_id = pil.location_id
                    AND k.color_id IS NOT DISTINCT FROM pil.color_id
                    AND k.movement_type = 'CORTE_INVENTARIO'
                    AND k.reference_number = (SELECT referencia FROM corte_param));

-- 4.3 Bodegas en 0 y lotes FIFO consumidos
UPDATE product_inventory_location pil
SET quantity = 0, sizes_data = NULL, updated_at = NOW()
FROM locations l
WHERE l.id = pil.location_id AND l.code IN (SELECT code FROM corte_ubicacion);

UPDATE product_fifo_batch b
SET quantity_available = 0
FROM locations l
WHERE l.id = b.location_id AND l.code IN (SELECT code FROM corte_ubicacion)
  AND b.quantity_available <> 0;

-- 4.4 Carga del respaldo: una fila de inventario por ubicación + producto + color
WITH r AS (
    SELECT l.id AS location_id, c.product_id, c.product_name, c.color_id, c.talla, c.por_talla,
           SUM(c.cantidad) AS cantidad
    FROM corte_carga_resuelta c
    JOIN locations l ON l.code = c.ubicacion
    WHERE c.cantidad > 0
    GROUP BY l.id, c.product_id, c.product_name, c.color_id, c.talla, c.por_talla
), fila AS (
    SELECT location_id, product_id, MAX(product_name) AS product_name, color_id,
           SUM(cantidad) AS total,
           CASE WHEN BOOL_OR(por_talla)
                THEN (jsonb_object_agg(talla, cantidad::int) FILTER (WHERE talla IS NOT NULL))::text
           END AS sizes_data
    FROM r
    GROUP BY location_id, product_id, color_id
), upd AS (
    UPDATE product_inventory_location pil
    SET quantity = f.total, sizes_data = f.sizes_data, updated_at = NOW()
    FROM fila f
    WHERE pil.product_id = f.product_id AND pil.location_id = f.location_id
      AND pil.color_id IS NOT DISTINCT FROM f.color_id
    RETURNING pil.product_id, pil.location_id, pil.color_id
)
INSERT INTO product_inventory_location
    (product_id, product_name, location_id, color_id, quantity, min, sizes_data, created_at, updated_at)
SELECT f.product_id, f.product_name, f.location_id, f.color_id, f.total, 0, f.sizes_data, NOW(), NOW()
FROM fila f
WHERE NOT EXISTS (SELECT 1 FROM upd u
                  WHERE u.product_id = f.product_id AND u.location_id = f.location_id
                    AND u.color_id IS NOT DISTINCT FROM f.color_id);

-- 4.5 Movimientos CARGA_INICIAL (uno por talla), con saldo acumulado
INSERT INTO product_inventory_kardex
    (product_id, location_id, color_id, size_label, movement_type, quantity, quantity_before,
     quantity_after, reference_type, reference_number, description, movement_date, created_at)
SELECT product_id, location_id, color_id, talla, 'CARGA_INICIAL', cantidad,
       acumulado - cantidad, acumulado,
       'CORTE_INVENTARIO', (SELECT referencia FROM corte_param),
       'Carga inicial desde respaldo de inventario',
       (SELECT fecha FROM corte_param), NOW()
FROM (
    SELECT l.id AS location_id, c.product_id, c.color_id, c.talla, SUM(c.cantidad) AS cantidad,
           SUM(SUM(c.cantidad)) OVER (PARTITION BY l.id, c.product_id, c.color_id
                                      ORDER BY c.talla NULLS FIRST) AS acumulado
    FROM corte_carga_resuelta c
    JOIN locations l ON l.code = c.ubicacion
    WHERE c.cantidad > 0
    GROUP BY l.id, c.product_id, c.color_id, c.talla
) x
ORDER BY location_id, product_id, color_id, talla NULLS FIRST;

-- Revise el PASO 5 antes de confirmar. Si algo no cuadra: ROLLBACK;
-- COMMIT;


-- =============================================================================
-- PASO 5. VERIFICACIÓN (correr antes del COMMIT y otra vez después)
-- =============================================================================
-- 5a. Inventario vs Excel por ubicación + producto + color: debe dar 0 filas
WITH excel AS (
    SELECT l.id AS location_id, c.product_id, c.color_id, SUM(c.cantidad) AS cantidad
    FROM corte_carga_resuelta c JOIN locations l ON l.code = c.ubicacion
    GROUP BY l.id, c.product_id, c.color_id
)
SELECT l.code, p.code AS producto, co.name AS color, pil.quantity AS sistema, e.cantidad AS excel
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors co ON co.id = pil.color_id
FULL JOIN excel e ON e.location_id = pil.location_id AND e.product_id = pil.product_id
                 AND e.color_id IS NOT DISTINCT FROM pil.color_id
WHERE l.code IN (SELECT code FROM corte_ubicacion)
  AND COALESCE(pil.quantity, 0) <> COALESCE(e.cantidad, 0);

-- 5b. Totales por ubicación (comparar con el Excel)
SELECT l.code, SUM(pil.quantity) AS unidades
FROM product_inventory_location pil JOIN locations l ON l.id = pil.location_id
WHERE l.code IN (SELECT code FROM corte_ubicacion)
GROUP BY l.code ORDER BY l.code;

-- 5c. Kardex = inventario (suma de toda la historia por fila): debe dar 0 filas
SELECT l.code, p.code AS producto, pil.color_id, pil.quantity, SUM(k.quantity) AS suma_kardex
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN product_inventory_kardex k
       ON k.product_id = pil.product_id AND k.location_id = pil.location_id
      AND k.color_id IS NOT DISTINCT FROM pil.color_id
WHERE l.code IN (SELECT code FROM corte_ubicacion)
GROUP BY l.code, p.code, pil.color_id, pil.quantity
HAVING pil.quantity <> COALESCE(SUM(k.quantity), 0);

-- Después del COMMIT: correr scripts/audit-cuadre-inventario-pt.sql con :desde = fecha del corte.


-- =============================================================================
-- DESHACER (solo si hace falta, con el respaldo del PASO 4.1)
-- =============================================================================
-- BEGIN;
-- DELETE FROM product_inventory_kardex
--  WHERE reference_type = 'CORTE_INVENTARIO' AND reference_number = (SELECT referencia FROM corte_param);
-- UPDATE product_inventory_location pil SET quantity = 0, sizes_data = NULL
--   FROM locations l WHERE l.id = pil.location_id AND l.code IN (SELECT code FROM corte_ubicacion);
-- UPDATE product_inventory_location pil SET quantity = b.quantity, sizes_data = b.sizes_data
--   FROM corte_bk_inventario b WHERE b.id = pil.id;
-- UPDATE product_fifo_batch f SET quantity_available = b.quantity_available
--   FROM corte_bk_fifo b WHERE b.id = f.id;
-- COMMIT;
