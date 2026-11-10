-- =======================================================================================
-- Distribución de envíos de reposición — SCRIPT 01: ANTES de desplegar el back.
--
-- POR QUÉ EXISTE
-- Desde este cambio, todo envío NUEVO de reposición a kiosco vive dentro de una distribución (manual, de
-- reposición sugerida o la de su OPK). El back nuevo lee columnas que hoy no existen
-- (spring.jpa.hibernate.ddl-auto=validate), así que este script va ANTES del despliegue.
--
-- QUÉ HACE
--   0. Respaldo de product_distribution.
--   1. Columnas nuevas: product_distribution.origin (MANUAL | REPOSICION),
--      product_distribution.replenishment_window_days y product_shipment_detail.suggested_quantity.
--   2. Verificaciones.
--
-- QUÉ NO HACE
--   NO mueve ningún envío. Los envíos históricos sin distribución (directos a kiosco y de OPK) se quedan
--   como están: pertenecen a distribuciones pasadas y la regla aplica solo de aquí en adelante.
--   Todas las distribuciones que ya existen quedan con origin = 'MANUAL'.
--   NO cambia ningún número de envío.
--
-- CÓMO CORRERLO
--   Termina en ROLLBACK: córrelo, revisa las verificaciones, cambia el ROLLBACK final por COMMIT y córrelo otra vez.
--   Después despliega el back y luego corre el SCRIPT 02.
-- =======================================================================================

BEGIN;

-- ---------------------------------------------------------------------------------------
-- 0. RESPALDO (solo se crea la primera vez; si ya existe, se conserva el primer respaldo).
-- ---------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bak_20261104_product_distribution AS
    SELECT * FROM product_distribution;

-- ---------------------------------------------------------------------------------------
-- 1. COLUMNAS NUEVAS (idempotente: correrlo dos veces no cambia nada la segunda)
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

-- ---------------------------------------------------------------------------------------
-- 2. VERIFICAR (antes de cambiar a COMMIT)
-- ---------------------------------------------------------------------------------------
-- 2.a Deben salir las 3 columnas.
SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE (table_name = 'product_distribution' AND column_name IN ('origin', 'replenishment_window_days'))
   OR (table_name = 'product_shipment_detail' AND column_name = 'suggested_quantity')
ORDER BY table_name, column_name;

-- 2.b Todas las distribuciones existentes quedan MANUAL; el total debe ser igual al del respaldo.
SELECT d.origin, count(*) AS distribuciones,
       (SELECT count(*) FROM bak_20261104_product_distribution) AS total_en_respaldo
FROM product_distribution d
GROUP BY d.origin;

-- 2.c Informativo: envíos históricos sin distribución. NO se tocan; el número debe ser el mismo antes y después.
SELECT CASE WHEN ps.production_order_id IS NULL THEN 'Directo a kiosco (histórico)' ELSE 'Con OP' END AS tipo,
       count(*) AS envios_sin_distribucion
FROM product_shipment ps
WHERE ps.distribution_id IS NULL
  AND ps.location_id IS NOT NULL
GROUP BY 1
ORDER BY 1;

-- =======================================================================================
-- REVERSA (comentada). Correr solo si se revierte también el back a la versión anterior,
-- porque el back nuevo exige estas columnas.
-- =======================================================================================
-- BEGIN;
-- ALTER TABLE product_distribution DROP CONSTRAINT IF EXISTS chk_product_distribution_origin;
-- ALTER TABLE product_distribution DROP COLUMN IF EXISTS origin;
-- ALTER TABLE product_distribution DROP COLUMN IF EXISTS replenishment_window_days;
-- ALTER TABLE product_shipment_detail DROP COLUMN IF EXISTS suggested_quantity;
-- COMMIT;
--
-- Cuando ya no haga falta el respaldo:
-- DROP TABLE bak_20261104_product_distribution;

-- =======================================================================================
-- FIN. Cambiar por COMMIT solo cuando las verificaciones cuadren.
-- =======================================================================================
ROLLBACK;
