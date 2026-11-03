-- =======================================================================================
-- Distribución de envíos de reposición — SCRIPT 01: ANTES de desplegar el back.
--
-- POR QUÉ EXISTE
-- Todo envío de reposición a kiosco vive ahora dentro de una distribución: la manual, la de reposición
-- sugerida y los envíos de OPK. El back nuevo lee columnas que hoy no existen (spring.jpa.hibernate.ddl-auto=validate),
-- así que este script va ANTES del despliegue: agrega las columnas y mete los envíos sueltos en su distribución.
--
-- QUÉ HACE
--   0. Respaldo de product_distribution y de los distribution_id de product_shipment y production_order.
--   1. Columnas nuevas: product_distribution.origin (MANUAL | REPOSICION), product_distribution.replenishment_window_days,
--      product_shipment_detail.suggested_quantity.
--   2. Relleno de envíos sueltos (ver el detalle en la sección 2).
--   3. Verificaciones.
-- NO cambia ningún número de envío. NO toca OPCK, Luis Felipe, OPI, ENVI, cinchos ni tienda en línea.
--
-- CÓMO CORRERLO
--   Termina en ROLLBACK: la primera vez córrelo tal cual y revisa los resultados de las verificaciones.
--   Si todo cuadra, cambia el ROLLBACK final por COMMIT y córrelo otra vez.
--   Después despliega el back y luego corre el SCRIPT 02.
-- =======================================================================================

-- ---------------------------------------------------------------------------------------
-- VERIFICAR ANTES (no modifica nada): cuántos envíos sueltos hay por tipo.
-- ---------------------------------------------------------------------------------------
SELECT CASE
           WHEN ps.production_order_id IS NOT NULL THEN 'OPK a kiosco'
           WHEN coalesce(ps.notes, '') ILIKE 'Reposición automática%' THEN 'Directo de reposición'
           ELSE 'Directo a kiosco'
       END AS tipo,
       count(*) AS envios_sin_distribucion
FROM product_shipment ps
LEFT JOIN production_order po ON po.id = ps.production_order_id
WHERE ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
  AND (ps.production_order_id IS NULL
       OR (upper(btrim(coalesce(po.order_type, ''))) = 'NORMAL'
           AND upper(btrim(coalesce(po.code, ''))) LIKE 'OPK%'
           AND upper(coalesce(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%'))
GROUP BY 1
ORDER BY 1;

BEGIN;

-- ---------------------------------------------------------------------------------------
-- 0. RESPALDO (solo se crea la primera vez; si ya existe, se conserva el primer respaldo).
-- ---------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bak_20261103_product_distribution AS
    SELECT * FROM product_distribution;
CREATE TABLE IF NOT EXISTS bak_20261103_product_shipment_distribution AS
    SELECT id, distribution_id FROM product_shipment;
CREATE TABLE IF NOT EXISTS bak_20261103_production_order_distribution AS
    SELECT id, distribution_id FROM production_order;

-- ---------------------------------------------------------------------------------------
-- 1. COLUMNAS NUEVAS
-- ---------------------------------------------------------------------------------------
ALTER TABLE product_distribution ADD COLUMN IF NOT EXISTS origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE product_distribution ADD COLUMN IF NOT EXISTS replenishment_window_days INTEGER;
ALTER TABLE product_shipment_detail ADD COLUMN IF NOT EXISTS suggested_quantity NUMERIC(12,3);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_distribution_origin') THEN
        ALTER TABLE product_distribution
            ADD CONSTRAINT chk_product_distribution_origin CHECK (origin IN ('MANUAL', 'REPOSICION'));
    END IF;
END $$;

-- 2. (sección de relleno, abajo)
-- ---------------------------------------------------------------------------------------
-- RELLENO: envíos de reposición a kiosco que no tienen distribución.
-- Idempotente: solo toca envíos con distribution_id IS NULL. Correrlo dos veces no cambia nada la segunda.
--
-- Qué se agrupa y en qué distribución:
--   a) Envíos de OPK a kiosco (OP NORMAL con código OPK…, fuera de Luis Felipe):
--        - si la OPK ya tiene distribution_id válido, el envío entra a esa distribución;
--        - si no, UNA distribución MANUAL por OPK ("Envíos de OPK-…") y se liga a la OPK.
--   b) Envíos directos a kiosco sin OP hechos por la reposición actual (notas "Reposición automática…"):
--        UNA distribución REPOSICION por día + usuario que los creó.
--   c) Los demás envíos directos a kiosco sin OP: UNA distribución MANUAL por envío
--        ("Envío directo a kiosko · <kiosco>"), igual que los crea el sistema desde ahora.
-- NO se tocan: OPCK, OPV/OPC de Luis Felipe, OPI, ENVI, cinchos, Entre Cueros, tienda en línea.
-- La numeración de envíos NO cambia: solo se llena distribution_id.
-- ---------------------------------------------------------------------------------------

DROP TABLE IF EXISTS tmp_dist_envio_grupo;
DROP TABLE IF EXISTS tmp_dist_grupo;

CREATE TEMP TABLE tmp_dist_envio_grupo (
    shipment_id         BIGINT PRIMARY KEY,
    grupo_key           TEXT   NOT NULL
) ON COMMIT DROP;

CREATE TEMP TABLE tmp_dist_grupo (
    grupo_key           TEXT PRIMARY KEY,
    tipo                TEXT NOT NULL,          -- OPK | REPOSICION | DIRECTO
    origin              TEXT NOT NULL,          -- MANUAL | REPOSICION
    production_order_id BIGINT,
    description         TEXT NOT NULL,
    first_at            TIMESTAMP NOT NULL,
    created_by          BIGINT,
    distribution_number TEXT
) ON COMMIT DROP;

-- a.1) OPK que ya tienen una distribución válida: el envío entra a esa.
UPDATE product_shipment ps
SET distribution_id = po.distribution_id,
    updated_at = CURRENT_TIMESTAMP
FROM production_order po
WHERE ps.production_order_id = po.id
  AND ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
  AND upper(btrim(coalesce(po.order_type, ''))) = 'NORMAL'
  AND upper(btrim(coalesce(po.code, ''))) LIKE 'OPK%'
  AND upper(coalesce(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%'
  AND po.distribution_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM product_distribution d WHERE d.id = po.distribution_id);

-- a.2) OPK sin distribución: una por OPK.
INSERT INTO tmp_dist_envio_grupo (shipment_id, grupo_key)
SELECT ps.id, 'OPK:' || po.id
FROM product_shipment ps
JOIN production_order po ON po.id = ps.production_order_id
WHERE ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
  AND upper(btrim(coalesce(po.order_type, ''))) = 'NORMAL'
  AND upper(btrim(coalesce(po.code, ''))) LIKE 'OPK%'
  AND upper(coalesce(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%';

INSERT INTO tmp_dist_grupo (grupo_key, tipo, origin, production_order_id, description, first_at, created_by)
SELECT 'OPK:' || po.id,
       'OPK',
       'MANUAL',
       po.id,
       'Envíos de ' || coalesce(po.code, 'OPK #' || po.id),
       coalesce(min(ps.created_at), CURRENT_TIMESTAMP),
       (array_agg(ps.created_by ORDER BY ps.id))[1]
FROM tmp_dist_envio_grupo eg
JOIN product_shipment ps ON ps.id = eg.shipment_id
JOIN production_order po ON po.id = ps.production_order_id
WHERE eg.grupo_key LIKE 'OPK:%'
GROUP BY po.id, po.code;

-- b) Directos de la reposición actual: por día + usuario.
INSERT INTO tmp_dist_envio_grupo (shipment_id, grupo_key)
SELECT ps.id,
       'REP:' || to_char(coalesce(ps.created_at, CURRENT_TIMESTAMP), 'YYYY-MM-DD') || ':' || coalesce(ps.created_by, 0)
FROM product_shipment ps
WHERE ps.distribution_id IS NULL
  AND ps.production_order_id IS NULL
  AND ps.location_id IS NOT NULL
  AND coalesce(ps.notes, '') ILIKE 'Reposición automática%';

INSERT INTO tmp_dist_grupo (grupo_key, tipo, origin, production_order_id, description, first_at, created_by)
SELECT eg.grupo_key,
       'REPOSICION',
       'REPOSICION',
       NULL,
       'Reposición automática (migración) · ' || to_char(min(ps.created_at), 'DD/MM/YYYY'),
       coalesce(min(ps.created_at), CURRENT_TIMESTAMP),
       (array_agg(ps.created_by ORDER BY ps.id))[1]
FROM tmp_dist_envio_grupo eg
JOIN product_shipment ps ON ps.id = eg.shipment_id
WHERE eg.grupo_key LIKE 'REP:%'
GROUP BY eg.grupo_key;

-- c) Los demás directos a kiosco sin OP: uno por envío.
INSERT INTO tmp_dist_envio_grupo (shipment_id, grupo_key)
SELECT ps.id, 'DIR:' || ps.id
FROM product_shipment ps
WHERE ps.distribution_id IS NULL
  AND ps.production_order_id IS NULL
  AND ps.location_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tmp_dist_envio_grupo x WHERE x.shipment_id = ps.id);

INSERT INTO tmp_dist_grupo (grupo_key, tipo, origin, production_order_id, description, first_at, created_by)
SELECT 'DIR:' || ps.id,
       'DIRECTO',
       'MANUAL',
       NULL,
       'Envío directo a kiosko · ' || coalesce(nullif(btrim(l.name), ''), l.code, 'Kiosko #' || ps.location_id),
       coalesce(ps.created_at, CURRENT_TIMESTAMP),
       ps.created_by
FROM tmp_dist_envio_grupo eg
JOIN product_shipment ps ON ps.id = eg.shipment_id
LEFT JOIN locations l ON l.id = ps.location_id
WHERE eg.grupo_key LIKE 'DIR:%';

-- Números DIST-YYYYMMDD-NNN: el correlativo final continúa desde el mayor en uso (igual que el back).
-- Ojo: lpad trunca en Postgres, por eso solo se rellena cuando el número tiene menos de 3 dígitos.
WITH base AS (
    SELECT coalesce(max((regexp_match(distribution_number, '^DIST-\d{8}-(\d+)$'))[1]::bigint), 0) AS v
    FROM product_distribution
),
ordenados AS (
    SELECT g.grupo_key, row_number() OVER (ORDER BY g.first_at, g.grupo_key) AS n
    FROM tmp_dist_grupo g
)
UPDATE tmp_dist_grupo g
SET distribution_number = 'DIST-' || to_char(g.first_at, 'YYYYMMDD') || '-'
        || CASE WHEN length((b.v + o.n)::text) >= 3 THEN (b.v + o.n)::text ELSE lpad((b.v + o.n)::text, 3, '0') END
FROM ordenados o, base b
WHERE o.grupo_key = g.grupo_key;

INSERT INTO product_distribution (distribution_number, distribution_date, status, description, origin,
                                  created_at, created_by, updated_at, updated_by)
SELECT g.distribution_number, g.first_at::date, 'IN_PROGRESS', g.description, g.origin,
       CURRENT_TIMESTAMP, g.created_by, CURRENT_TIMESTAMP, g.created_by
FROM tmp_dist_grupo g;

UPDATE product_shipment ps
SET distribution_id = d.id,
    updated_at = CURRENT_TIMESTAMP
FROM tmp_dist_envio_grupo eg
JOIN tmp_dist_grupo g ON g.grupo_key = eg.grupo_key
JOIN product_distribution d ON d.distribution_number = g.distribution_number
WHERE ps.id = eg.shipment_id
  AND ps.distribution_id IS NULL;

-- La OPK queda ligada a su distribución nueva.
UPDATE production_order po
SET distribution_id = d.id,
    updated_at = CURRENT_TIMESTAMP
FROM tmp_dist_grupo g
JOIN product_distribution d ON d.distribution_number = g.distribution_number
WHERE g.tipo = 'OPK'
  AND po.id = g.production_order_id
  AND (po.distribution_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM product_distribution x WHERE x.id = po.distribution_id));

-- Estado de cada distribución nueva según sus envíos (mismas reglas del back):
--   todos recibidos → COMPLETED; todos ya procesados (enviados, recibidos o cancelados) → DISPATCHED;
--   si no → IN_PROGRESS.
UPDATE product_distribution d
SET status = CASE
        WHEN s.bool_and_rec THEN 'COMPLETED'
        WHEN s.bool_and_term THEN 'DISPATCHED'
        ELSE 'IN_PROGRESS'
    END,
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT ps.distribution_id,
           bool_and(upper(btrim(coalesce(ps.status, ''))) IN ('DELIVERED', 'RECEIVED', 'COMPLETED')) AS bool_and_rec,
           bool_and(upper(btrim(coalesce(ps.status, ''))) IN ('SENT', 'DELIVERED', 'RECEIVED', 'COMPLETED', 'CANCELLED')) AS bool_and_term
    FROM product_shipment ps
    JOIN product_distribution nd ON nd.id = ps.distribution_id
    JOIN tmp_dist_grupo g ON g.distribution_number = nd.distribution_number
    GROUP BY ps.distribution_id
) s
WHERE d.id = s.distribution_id;

-- Resumen de lo que hizo este relleno (filas = grupos creados).
SELECT g.tipo, g.origin, count(*) AS distribuciones_creadas,
       sum((SELECT count(*) FROM tmp_dist_envio_grupo eg WHERE eg.grupo_key = g.grupo_key)) AS envios_ligados
FROM tmp_dist_grupo g
GROUP BY g.tipo, g.origin
ORDER BY g.tipo;

-- ---------------------------------------------------------------------------------------
-- 3. VERIFICAR DESPUÉS (antes de cambiar a COMMIT)
-- ---------------------------------------------------------------------------------------
-- 3.a Debe devolver 0: envíos a kiosco sin OP que siguen sin distribución.
SELECT count(*) AS directos_sin_distribucion
FROM product_shipment
WHERE distribution_id IS NULL AND production_order_id IS NULL AND location_id IS NOT NULL;

-- 3.b Debe devolver 0: envíos de OPK a kiosco sin distribución.
SELECT count(*) AS opk_sin_distribucion
FROM product_shipment ps
JOIN production_order po ON po.id = ps.production_order_id
WHERE ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
  AND upper(btrim(coalesce(po.order_type, ''))) = 'NORMAL'
  AND upper(btrim(coalesce(po.code, ''))) LIKE 'OPK%'
  AND upper(coalesce(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%';

-- 3.c Debe devolver 0 filas: ningún número de distribución repetido.
SELECT distribution_number, count(*)
FROM product_distribution
GROUP BY distribution_number
HAVING count(*) > 1;

-- 3.d Debe devolver 0: el relleno no creó ni borró envíos (compara contra el respaldo).
SELECT count(*) AS envios_nuevos_o_perdidos
FROM product_shipment ps
FULL JOIN bak_20261103_product_shipment_distribution b ON b.id = ps.id
WHERE ps.id IS NULL OR b.id IS NULL;

-- 3.e Informativo: OPCK, Luis Felipe, OPI, ENVI y cinchos siguen sin distribución (el número no debe bajar).
SELECT coalesce(upper(po.order_type), 'SIN OP') AS tipo_orden, count(*) AS envios_sin_distribucion
FROM product_shipment ps
LEFT JOIN production_order po ON po.id = ps.production_order_id
WHERE ps.distribution_id IS NULL
GROUP BY 1
ORDER BY 1;


-- =======================================================================================
-- REVERSA (comentada). Correr solo si se revierte también el back a la versión anterior,
-- porque el back nuevo exige las columnas. Deshace los distribution_id que puso este script
-- y borra las distribuciones que creó y que ya nadie usa.
-- =======================================================================================
-- BEGIN;
-- UPDATE product_shipment ps
-- SET distribution_id = b.distribution_id
-- FROM bak_20261103_product_shipment_distribution b
-- WHERE b.id = ps.id AND ps.distribution_id IS DISTINCT FROM b.distribution_id;
--
-- UPDATE production_order po
-- SET distribution_id = b.distribution_id
-- FROM bak_20261103_production_order_distribution b
-- WHERE b.id = po.id AND po.distribution_id IS DISTINCT FROM b.distribution_id;
--
-- DELETE FROM product_distribution d
-- WHERE NOT EXISTS (SELECT 1 FROM bak_20261103_product_distribution b WHERE b.id = d.id)
--   AND NOT EXISTS (SELECT 1 FROM product_shipment ps WHERE ps.distribution_id = d.id)
--   AND NOT EXISTS (SELECT 1 FROM production_order po WHERE po.distribution_id = d.id);
--
-- ALTER TABLE product_distribution DROP CONSTRAINT IF EXISTS chk_product_distribution_origin;
-- ALTER TABLE product_distribution DROP COLUMN IF EXISTS origin;
-- ALTER TABLE product_distribution DROP COLUMN IF EXISTS replenishment_window_days;
-- ALTER TABLE product_shipment_detail DROP COLUMN IF EXISTS suggested_quantity;
-- COMMIT;
--
-- Cuando ya no hagan falta los respaldos:
-- DROP TABLE bak_20261103_product_distribution;
-- DROP TABLE bak_20261103_product_shipment_distribution;
-- DROP TABLE bak_20261103_production_order_distribution;

-- =======================================================================================
-- FIN. Cambiar por COMMIT solo cuando las verificaciones cuadren.
-- =======================================================================================
ROLLBACK;
