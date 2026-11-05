-- =======================================================================================
-- Distribución de envíos de reposición — SCRIPT 02: DESPUÉS de desplegar el back.
--
-- POR QUÉ EXISTE
-- Pone la regla en la base, solo de aquí en adelante: un envío NUEVO a kiosco sin orden de producción
-- debe tener distribución. La regla de las OPK (sus envíos a kiosco van a la distribución de la OPK) la
-- valida el back.
--
-- LOS HISTÓRICOS NO SE TOCAN
-- Los envíos que ya existen al correr este script quedan exentos: la regla compara contra el created_at
-- más reciente de product_shipment en ese momento (el mismo reloj con el que el back guarda created_at).
-- Así se pueden seguir enviando, recibiendo y editando como hoy.
-- No se pone NOT NULL a la columna porque OPCK, Luis Felipe, OPI, ENVI y cinchos siguen sin distribución.
--
-- CÓMO CORRERLO
--   Termina en ROLLBACK: córrelo, revisa las verificaciones, cambia el ROLLBACK por COMMIT y córrelo otra vez.
--   Requiere el back nuevo ya desplegado (el viejo seguiría creando envíos directos sin distribución).
-- =======================================================================================

BEGIN;

-- ---------------------------------------------------------------------------------------
-- 0. RESPALDO de la definición actual (para saber si ya había una regla con este nombre).
-- ---------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bak_20261104_product_shipment_constraints AS
    SELECT conname, pg_get_constraintdef(oid) AS definicion, CURRENT_TIMESTAMP AS respaldado_en
    FROM pg_constraint
    WHERE conrelid = 'product_shipment'::regclass;

-- ---------------------------------------------------------------------------------------
-- 1. REGLA: envío a kiosco sin OP necesita distribución, salvo los que ya existían.
--    El corte es el created_at más reciente al correr el script. NOT VALID + VALIDATE no bloquea la tabla
--    mientras revisa las filas existentes.
-- ---------------------------------------------------------------------------------------
-- Excepción autorizada (ver script 03): envío directo a kiosko sin distribución.
ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS without_distribution BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
DECLARE
    corte TIMESTAMP;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_shipment_kiosk_distribution') THEN
        SELECT coalesce(max(created_at), LOCALTIMESTAMP) INTO corte FROM product_shipment;
        EXECUTE format(
            'ALTER TABLE product_shipment ADD CONSTRAINT chk_product_shipment_kiosk_distribution '
            'CHECK (distribution_id IS NOT NULL OR production_order_id IS NOT NULL OR location_id IS NULL '
            'OR without_distribution OR created_at IS NULL OR created_at <= %L::timestamp) NOT VALID',
            corte);
    END IF;
END $$;

ALTER TABLE product_shipment VALIDATE CONSTRAINT chk_product_shipment_kiosk_distribution;

-- ---------------------------------------------------------------------------------------
-- 2. VERIFICAR
-- ---------------------------------------------------------------------------------------
-- 2.a Debe devolver una fila con convalidated = true. La definición muestra la fecha de corte.
SELECT conname, convalidated, pg_get_constraintdef(oid) AS definicion
FROM pg_constraint
WHERE conname = 'chk_product_shipment_kiosk_distribution';

-- 2.b Informativo: envíos históricos sin distribución que quedan exentos (los mismos de antes, no se tocan).
SELECT CASE WHEN ps.production_order_id IS NULL THEN 'Directo a kiosco (histórico)' ELSE 'Con OP' END AS tipo,
       count(*) AS envios_sin_distribucion
FROM product_shipment ps
WHERE ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
GROUP BY 1
ORDER BY 1;

-- 2.c Informativo: distribuciones por origen y estado.
SELECT origin, status, count(*) FROM product_distribution GROUP BY origin, status ORDER BY origin, status;

-- =======================================================================================
-- REVERSA (comentada): quita la regla.
-- =======================================================================================
-- BEGIN;
-- ALTER TABLE product_shipment DROP CONSTRAINT IF EXISTS chk_product_shipment_kiosk_distribution;
-- COMMIT;
--
-- Cuando ya no haga falta el respaldo:
-- DROP TABLE bak_20261104_product_shipment_constraints;

-- =======================================================================================
-- FIN. Cambiar por COMMIT solo cuando las verificaciones cuadren.
-- =======================================================================================
ROLLBACK;
