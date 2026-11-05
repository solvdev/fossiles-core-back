-- =======================================================================================
-- Envío directo a kiosko SIN distribución (excepción autorizada).
-- ORDEN: correr ESTE script ANTES de desplegar el back (ddl-auto=validate exige la columna).
-- Es seguro correrlo con el back actual: solo agrega una columna con default false y
-- reemplaza la regla por una equivalente con una salida más.
-- =======================================================================================
BEGIN;

-- 0. Respaldo de la definición actual de la regla.
CREATE TABLE IF NOT EXISTS bak_20261105_product_shipment_constraints AS
    SELECT conname, pg_get_constraintdef(oid) AS definicion, CURRENT_TIMESTAMP AS respaldado_en
    FROM pg_constraint
    WHERE conrelid = 'product_shipment'::regclass;

-- 1. Columna marcadora. Los envíos existentes quedan en false (sin excepción).
ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS without_distribution BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Misma regla de antes (conserva la fecha de corte) + salida para la excepción.
DO $$
DECLARE
    def TEXT;
    corte TEXT;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO def
    FROM pg_constraint
    WHERE conname = 'chk_product_shipment_kiosk_distribution'
      AND conrelid = 'product_shipment'::regclass;

    IF def IS NULL THEN
        -- Base donde todavía no se corrió el script 02: solo queda la columna. El 02 ya crea la regla con la excepción.
        RAISE NOTICE 'No existe chk_product_shipment_kiosk_distribution (script 02 pendiente): solo se agregó la columna.';
        RETURN;
    END IF;

    IF def LIKE '%without_distribution%' THEN
        RAISE NOTICE 'La regla ya incluye without_distribution; no se cambia.';
        RETURN;
    END IF;

    -- La fecha de corte vive en la definición: created_at <= '2026-..'::timestamp
    corte := substring(def FROM 'created_at <= ''([^'']+)''');
    IF corte IS NULL THEN
        RAISE EXCEPTION 'No se pudo leer la fecha de corte de la regla: %', def;
    END IF;

    ALTER TABLE product_shipment DROP CONSTRAINT chk_product_shipment_kiosk_distribution;
    EXECUTE format(
        'ALTER TABLE product_shipment ADD CONSTRAINT chk_product_shipment_kiosk_distribution '
        'CHECK (distribution_id IS NOT NULL OR production_order_id IS NOT NULL OR location_id IS NULL '
        'OR without_distribution OR created_at IS NULL OR created_at <= %L::timestamp)',
        corte);
END $$;

-- 3. VERIFICAR
SELECT conname, convalidated, pg_get_constraintdef(oid) AS definicion
FROM pg_constraint
WHERE conname = 'chk_product_shipment_kiosk_distribution';

SELECT count(*) AS marcados_sin_distribucion FROM product_shipment WHERE without_distribution;

-- Cambie a COMMIT cuando la verificación se vea bien.
ROLLBACK;

-- ---------------------------------------------------------------------------------------
-- REVERSA (solo si no hay envíos con without_distribution = true):
--   ALTER TABLE product_shipment DROP CONSTRAINT chk_product_shipment_kiosk_distribution;
--   -- recrear con la definición guardada en bak_20261105_product_shipment_constraints
--   ALTER TABLE product_shipment DROP COLUMN without_distribution;
-- ---------------------------------------------------------------------------------------
