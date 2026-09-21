-- Relleno de una sola vez: correlativo ENVP para las órdenes OPV que se quedaron sin él.
--
-- POR QUÉ EXISTE
-- Hasta ahora el correlativo se asignaba también al LEER: GET /api/production-orders
-- mapeaba cada fila con ensureOpvVendorShipmentNumber, o sea que abrir el listado escribía
-- en la base. Se quita de ahí, y este script cubre a las que nunca hayan pasado por crear
-- o actualizar desde que esas rutas lo asignan.
--
-- CUÁNDO CORRERLO
-- ANTES de desplegar la versión que quita la asignación de getAll(). Si se despliega primero
-- y se rellena después, las órdenes viejas se quedan sin número hasta que alguien las edite.
--
-- ES IDEMPOTENTE: solo toca filas sin número. Correrlo dos veces no cambia nada la segunda.
--
-- ESTADO EN LA COPIA LOCAL (17/09/2026): 104 órdenes de Luis Felipe, **ninguna sin número**,
-- así que aquí no actualiza ni una fila. El máximo ENVP en uso es 105. No se ha comprobado
-- contra la base real: correr primero el SELECT de verificación de abajo.

-- ---------------------------------------------------------------------------------------
-- 1. VERIFICAR ANTES (no modifica nada). Si devuelve 0, no hay nada que rellenar.
-- ---------------------------------------------------------------------------------------
-- SELECT count(*) AS sin_numero
-- FROM production_order
-- WHERE upper(coalesce(seller_name, '')) LIKE '%LUIS FELIPE%'
--   AND (vendor_shipment_number IS NULL OR btrim(vendor_shipment_number) = '');

-- ---------------------------------------------------------------------------------------
-- 2. RELLENO
--
-- Reproduce lo que hace OpvVendorShipmentNumberService.nextFreeNumber(): toma el mayor
-- correlativo en uso mirando LAS DOS tablas —production_order y product_shipment— y reparte
-- los siguientes en orden de creación. Arrancar por encima del máximo garantiza que ninguno
-- de los asignados esté ya ocupado, así que no hace falta comprobar uno por uno.
--
-- La condición del vendedor es la misma que isOpvVendorShipmentFlow: el nombre contiene
-- "LUIS FELIPE". El formato es ENVP-00000, con cinco dígitos, igual que String.format.
-- ---------------------------------------------------------------------------------------
BEGIN;

WITH max_seq AS (
    SELECT COALESCE(MAX(seq), 0) AS valor
    FROM (
        SELECT (regexp_match(vendor_shipment_number, '^ENVP-(\d+)$'))[1]::bigint AS seq
        FROM production_order
        WHERE vendor_shipment_number ~ '^ENVP-\d+$'
        UNION ALL
        SELECT (regexp_match(shipment_number, '^ENVP-(\d+)$'))[1]::bigint
        FROM product_shipment
        WHERE shipment_number ~ '^ENVP-\d+$'
    ) t
),
faltantes AS (
    SELECT id,
           row_number() OVER (ORDER BY created_at NULLS LAST, id) AS orden
    FROM production_order
    WHERE upper(coalesce(seller_name, '')) LIKE '%LUIS FELIPE%'
      AND (vendor_shipment_number IS NULL OR btrim(vendor_shipment_number) = '')
)
UPDATE production_order po
SET vendor_shipment_number = 'ENVP-' || lpad((m.valor + f.orden)::text, 5, '0'),
    updated_at = CURRENT_TIMESTAMP
FROM faltantes f, max_seq m
WHERE po.id = f.id;

-- ---------------------------------------------------------------------------------------
-- 3. COMPROBAR ANTES DE CONFIRMAR: ningún correlativo puede estar repetido.
--    Si esta consulta devuelve alguna fila, hacer ROLLBACK en vez de COMMIT.
-- ---------------------------------------------------------------------------------------
SELECT vendor_shipment_number, count(*)
FROM production_order
WHERE vendor_shipment_number IS NOT NULL
GROUP BY vendor_shipment_number
HAVING count(*) > 1;

COMMIT;
