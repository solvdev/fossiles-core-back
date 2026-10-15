-- =============================================================================
-- LIMPIEZA DE INVENTARIO DE BODEGAS (producto terminado) — PostgreSQL
-- =============================================================================
-- Deja las bodegas indicadas en 0 y sin historia:
--   * product_inventory_location  -> cantidad 0 y sin desglose por talla
--   * product_inventory_kardex    -> los movimientos de esas ubicaciones pasan a
--                                    product_inventory_kardex_historico (se consultan en la
--                                    pantalla "Kardex histórico"); el ledger activo queda vacío
--                                    y un recálculo da 0
--   * product_fifo_batch          -> se borran los lotes de costo de esas ubicaciones
-- Requiere scripts/migration-product-kardex-historico.sql (tabla del histórico).
-- Inventario y lotes FIFO se copian a tablas limpieza_bk_* (hay instrucciones para deshacer).
-- Después, el inventario se carga a mano con Ajustes de inventario (stock sistema 0 → conteo).
--
-- NO toca kioskos (kiosco_stock / kiosco_movement) ni otras ubicaciones fuera de la lista.
--
-- Uso (DBeaver o psql, en UNA misma sesión, en orden):
--   PASO 0  Ubicaciones a limpiar.
--   PASO 1  Revisiones previas: TODAS deben dar 0 filas antes de seguir.
--   PASO 2  Limpiar (una sola transacción).
--   PASO 3  Verificar y COMMIT.
-- =============================================================================


-- =============================================================================
-- PASO 0. REFERENCIA Y UBICACIONES A LIMPIAR
-- =============================================================================
DROP TABLE IF EXISTS limpieza_param;
CREATE TABLE limpieza_param AS
SELECT 'LIMPIEZA-20261020'::varchar(100) AS referencia;   -- <== nombre con el que se verá en el histórico

-- Ver ubicaciones con inventario de producto:
--   SELECT l.id, l.code, l.name, l.categoria, COUNT(pil.id) AS filas, SUM(pil.quantity) AS unidades
--   FROM locations l JOIN product_inventory_location pil ON pil.location_id = l.id
--   GROUP BY l.id ORDER BY unidades DESC NULLS LAST;
DROP TABLE IF EXISTS limpieza_ubicacion;
CREATE TABLE limpieza_ubicacion (code varchar(15) PRIMARY KEY);
INSERT INTO limpieza_ubicacion (code) VALUES
    ('BODEGA_PT'),        -- id 38, Bodega Producto Terminado
    ('DEVOLUCION'),       -- id 41, Devoluciones de producto terminado
    ('DEV_NO_APTA'),      -- puede no existir todavía: no pasa nada
    ('EN_TRANSITO');      -- puede no existir todavía: no pasa nada

-- 0b. Lo que se va a limpiar (revise que sean solo bodegas, nunca kioskos)
SELECT l.code, l.name, l.categoria,
       (SELECT COUNT(*) FROM product_inventory_location p WHERE p.location_id = l.id) AS filas_inventario,
       (SELECT COALESCE(SUM(p.quantity), 0) FROM product_inventory_location p WHERE p.location_id = l.id) AS unidades,
       (SELECT COUNT(*) FROM product_inventory_kardex k WHERE k.location_id = l.id) AS movimientos_kardex,
       (SELECT COUNT(*) FROM product_fifo_batch b WHERE b.location_id = l.id) AS lotes_fifo
FROM locations l
WHERE l.code IN (SELECT code FROM limpieza_ubicacion)
ORDER BY l.code;


-- =============================================================================
-- PASO 1. REVISIONES PREVIAS (todas deben dar 0 filas)
-- =============================================================================
-- Sin historia en el kardex, el sistema ya no sabe qué documentos descontaron bodega.
-- Si quedan documentos a medio camino, al moverlos después de la limpieza descontarían
-- otra vez del inventario nuevo. Ciérrelos antes de limpiar.

-- 1a. Envíos en camino (SENT): que el kiosko los reciba, o anúlelos ANTES de limpiar.
SELECT s.id, s.shipment_number, s.status, s.sent_at, l.name AS destino
FROM product_shipment s LEFT JOIN locations l ON l.id = s.location_id
WHERE s.status = 'SENT'
ORDER BY s.sent_at;

-- 1b. Ventas en línea que ya salieron de bodega (preparadas) y no se han despachado:
--     despáchelas o anúlelas ANTES de limpiar.
SELECT os.id, os.sale_number, os.status, os.customer_name
FROM online_sale os
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'DEVOLUCION', 'ANULADA')
  AND COALESCE((SELECT SUM(k.quantity) FROM product_inventory_kardex k
                WHERE k.reference_id = os.id
                  AND k.reference_type IN ('ONLINE_SALE_PREPARE', 'ONLINE_SALE_DISPATCH')), 0) < 0;

-- 1c. Envíos del módulo anterior de distribución en tránsito: recíbalos ANTES de limpiar.
SELECT e.id, e.numero_envio, e.estado
FROM envio e
WHERE UPPER(e.estado) = 'EN_TRANSITO';


-- =============================================================================
-- PASO 2. LIMPIAR (todo o nada)
-- =============================================================================
BEGIN;

-- 2.1 Respaldos (bórrelos solo cuando el inventario nuevo esté cargado y revisado)
DROP TABLE IF EXISTS limpieza_bk_inventario;
CREATE TABLE limpieza_bk_inventario AS
SELECT pil.* FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
WHERE l.code IN (SELECT code FROM limpieza_ubicacion);

DROP TABLE IF EXISTS limpieza_bk_fifo;
CREATE TABLE limpieza_bk_fifo AS
SELECT b.* FROM product_fifo_batch b
JOIN locations l ON l.id = b.location_id
WHERE l.code IN (SELECT code FROM limpieza_ubicacion);

-- 2.2 Kardex (ledger) de esas bodegas: pasa al histórico y el activo queda vacío
INSERT INTO product_inventory_kardex_historico
    (id, product_id, location_id, color_id, size_label, movement_type, quantity, quantity_before,
     quantity_after, unit_cost, total_cost, reference_type, reference_id, reference_number,
     reference_line_id, description, movement_date, created_at, created_by, archive_reference, archived_at)
SELECT k.id, k.product_id, k.location_id, k.color_id, k.size_label, k.movement_type, k.quantity,
       k.quantity_before, k.quantity_after, k.unit_cost, k.total_cost, k.reference_type, k.reference_id,
       k.reference_number, k.reference_line_id, k.description, k.movement_date, k.created_at, k.created_by,
       (SELECT referencia FROM limpieza_param), NOW()
FROM product_inventory_kardex k
JOIN locations l ON l.id = k.location_id
WHERE l.code IN (SELECT code FROM limpieza_ubicacion);

DELETE FROM product_inventory_kardex k
USING locations l
WHERE l.id = k.location_id AND l.code IN (SELECT code FROM limpieza_ubicacion);

-- 2.3 Lotes de costo FIFO: fuera
DELETE FROM product_fifo_batch b
USING locations l
WHERE l.id = b.location_id AND l.code IN (SELECT code FROM limpieza_ubicacion);

-- 2.4 Inventario en 0 (las filas se conservan: los ajustes las vuelven a llenar)
UPDATE product_inventory_location pil
SET quantity = 0, sizes_data = NULL, updated_at = NOW()
FROM locations l
WHERE l.id = pil.location_id AND l.code IN (SELECT code FROM limpieza_ubicacion);


-- =============================================================================
-- PASO 3. VERIFICAR (antes del COMMIT): debe salir todo en 0
-- =============================================================================
SELECT l.code,
       (SELECT COALESCE(SUM(ABS(p.quantity)), 0) FROM product_inventory_location p WHERE p.location_id = l.id) AS unidades,
       (SELECT COUNT(*) FROM product_inventory_location p
         WHERE p.location_id = l.id AND COALESCE(p.sizes_data, '') NOT IN ('', '{}')) AS filas_con_tallas,
       (SELECT COUNT(*) FROM product_inventory_kardex k WHERE k.location_id = l.id) AS movimientos_kardex,
       (SELECT COUNT(*) FROM product_fifo_batch b WHERE b.location_id = l.id) AS lotes_fifo
FROM locations l
WHERE l.code IN (SELECT code FROM limpieza_ubicacion)
ORDER BY l.code;

-- Respaldos e histórico (deben coincidir con lo que mostró el PASO 0b)
SELECT (SELECT COUNT(*) FROM limpieza_bk_inventario) AS filas_inventario,
       (SELECT COALESCE(SUM(quantity), 0) FROM limpieza_bk_inventario) AS unidades,
       (SELECT COUNT(*) FROM product_inventory_kardex_historico
         WHERE archive_reference = (SELECT referencia FROM limpieza_param)) AS movimientos_en_historico,
       (SELECT COUNT(*) FROM limpieza_bk_fifo) AS lotes_fifo;

-- Si todo está bien:
-- COMMIT;
-- Si algo no está bien:
-- ROLLBACK;


-- =============================================================================
-- DESHACER (solo si hace falta, mientras existan las tablas limpieza_bk_* y el histórico)
-- =============================================================================
-- Ojo: borra también los ajustes y movimientos que se hayan hecho DESPUÉS de la limpieza
-- en esas bodegas.
-- BEGIN;
-- DELETE FROM product_inventory_kardex k USING locations l
--  WHERE l.id = k.location_id AND l.code IN (SELECT code FROM limpieza_ubicacion);
-- INSERT INTO product_inventory_kardex
--     (id, product_id, location_id, color_id, size_label, movement_type, quantity, quantity_before,
--      quantity_after, unit_cost, total_cost, reference_type, reference_id, reference_number,
--      reference_line_id, description, movement_date, created_at, created_by)
-- SELECT id, product_id, location_id, color_id, size_label, movement_type, quantity, quantity_before,
--        quantity_after, unit_cost, total_cost, reference_type, reference_id, reference_number,
--        reference_line_id, description, movement_date, created_at, created_by
-- FROM product_inventory_kardex_historico WHERE archive_reference = (SELECT referencia FROM limpieza_param);
-- DELETE FROM product_inventory_kardex_historico WHERE archive_reference = (SELECT referencia FROM limpieza_param);
-- DELETE FROM product_fifo_batch b USING locations l
--  WHERE l.id = b.location_id AND l.code IN (SELECT code FROM limpieza_ubicacion);
-- INSERT INTO product_fifo_batch SELECT * FROM limpieza_bk_fifo;
-- UPDATE product_inventory_location pil SET quantity = b.quantity, sizes_data = b.sizes_data
--   FROM limpieza_bk_inventario b WHERE b.id = pil.id;
-- COMMIT;
