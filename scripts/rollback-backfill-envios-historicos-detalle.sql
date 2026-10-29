-- Borra solo las líneas de product_shipment_detail que creó
-- scripts/backfill-envios-historicos-detalle.sql.
--
-- Las reconoce por la marca created_at = 2026-10-29 00:00:00 y solo en envíos con
-- notes LIKE 'ENVIO HISTORICO%' y status IN ('CONFIRMED', 'SENT').
-- Una segunda corrida no encuentra esas filas y borra 0. Es una sola transacción.
-- No toca inventario, kardex, customer_account_entry, product_shipment ni ninguna otra tabla.
--
-- Eduardo lo corre a mano. Primero en una base local. En producción, solo después de un
-- pg_dump. Este archivo no se ha ejecutado en ninguna base.
--
--   psql -v ON_ERROR_STOP=1 -f scripts/rollback-backfill-envios-historicos-detalle.sql
--
-- Así muestra lo que borraría y termina en ROLLBACK. Para guardar el borrado:
--
--   psql -v ON_ERROR_STOP=1 -v aplicar=si -f scripts/rollback-backfill-envios-historicos-detalle.sql
--
-- MARCA_CREATED_AT = 2026-10-29 00:00:00
-- Tiene que ser el mismo instante que en el relleno. No cambiar una sola de las dos.
\set ON_ERROR_STOP on

\if :{?aplicar}
\else
  \set aplicar no
\endif

SELECT current_database() AS base_destino;

SELECT CASE WHEN lower(btrim(:'aplicar')) = 'si' THEN 'yes' ELSE 'no' END AS rollback_commit \gset

BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE a_borrar ON COMMIT DROP AS
SELECT d.id,
       d.shipment_id,
       s.shipment_number,
       po.code AS order_code,
       s.status,
       d.quantity,
       d.unit_price
FROM product_shipment_detail d
JOIN product_shipment s ON s.id = d.shipment_id
LEFT JOIN production_order po ON po.id = s.production_order_id
WHERE s.notes LIKE 'ENVIO HISTORICO%'
  AND s.status IN ('CONFIRMED', 'SENT')
  AND d.created_at = TIMESTAMP '2026-10-29 00:00:00';

SELECT 'RESUMEN' AS seccion,
       count(*) AS lineas,
       count(DISTINCT shipment_id) AS envios,
       coalesce(sum(quantity), 0) AS unidades
FROM a_borrar;

SELECT 'VISTA_ROLLBACK' AS seccion,
       shipment_number AS numero_envio,
       order_code AS orden,
       status AS estado_envio,
       count(*) AS lineas,
       sum(quantity) AS unidades,
       round(coalesce(sum(round(quantity * unit_price, 2)), 0), 2) AS subtotal
FROM a_borrar
GROUP BY shipment_id, shipment_number, order_code, status
ORDER BY shipment_number;

DO $$
DECLARE
    esperadas integer;
    borradas integer;
    quedan integer;
BEGIN
    SELECT count(*) INTO esperadas FROM a_borrar;

    DELETE FROM product_shipment_detail d
    USING a_borrar b, product_shipment s
    WHERE d.id = b.id
      AND s.id = d.shipment_id
      AND s.notes LIKE 'ENVIO HISTORICO%'
      AND s.status IN ('CONFIRMED', 'SENT')
      AND d.created_at = TIMESTAMP '2026-10-29 00:00:00';

    GET DIAGNOSTICS borradas = ROW_COUNT;

    IF borradas IS DISTINCT FROM esperadas THEN
        RAISE EXCEPTION
            'El rollback borraría % líneas y la vista previa tenía %. No se confirmó nada.',
            borradas, esperadas;
    END IF;

    SELECT count(*)
      INTO quedan
      FROM product_shipment_detail d
      JOIN product_shipment s ON s.id = d.shipment_id
     WHERE s.notes LIKE 'ENVIO HISTORICO%'
       AND s.status IN ('CONFIRMED', 'SENT')
       AND d.created_at = TIMESTAMP '2026-10-29 00:00:00';

    IF quedan <> 0 THEN
        RAISE EXCEPTION
            'Quedaron % líneas con la marca del relleno. No se confirmó nada.', quedan;
    END IF;

    IF esperadas = 0 THEN
        RAISE NOTICE 'SIN_CAMBIOS: no hay líneas con la marca 2026-10-29 00:00:00. No se borró nada.';
    ELSE
        RAISE NOTICE 'Se borraron % líneas de detalle histórico dentro de la transacción.', esperadas;
    END IF;
END $$;

SELECT 'CIERRE' AS seccion,
       CASE WHEN :'rollback_commit' = 'yes' THEN 'COMMIT' ELSE 'ROLLBACK' END AS modo;

\if :rollback_commit
COMMIT;
\else
ROLLBACK;
\endif
