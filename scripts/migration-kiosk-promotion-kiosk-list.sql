-- Promociones con varios kioscos (lista) en lugar de uno solo.
-- CORRER ANTES DE DESPLEGAR EL BACK (ddl-auto=validate). Es idempotente.
--
-- kiosk_location_ids: ids separados por coma ("3,7,12"). NULL o vacío = todos los kioscos
-- (incluidos los que se creen después). La columna vieja kiosk_location_id se sigue respetando
-- cuando no hay lista, así que las promociones existentes no cambian.

BEGIN;

ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS kiosk_location_ids TEXT;

-- Las promociones que ya tenían un kiosco lo conservan también en la lista.
UPDATE kiosk_promotion
SET kiosk_location_ids = CAST(kiosk_location_id AS TEXT)
WHERE kiosk_location_id IS NOT NULL
  AND (kiosk_location_ids IS NULL OR TRIM(kiosk_location_ids) = '');

COMMIT;
