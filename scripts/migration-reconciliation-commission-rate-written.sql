-- El porcentaje de comisión de la liquidación se escribe (ya no solo 3%, 3.5% o 4%).
-- Reemplaza el check de recon_case.carrier_commission_rate. Idempotente.
-- Correr en fosstest y luego en fossilesgt, ANTES de desplegar el back.
-- La migración anterior ya dejó la columna; este script solo abre el rango.
--
--   carrier_commission_rate  fracción escrita en la venta (3.25% = 0.0325), mayor a 0 y hasta 1 (100%).
--                            Forza y Guatex siguen mostrando 3.5% cuando aquí está NULL.

ALTER TABLE recon_case DROP CONSTRAINT IF EXISTS chk_recon_case_commission_rate;
ALTER TABLE recon_case ADD CONSTRAINT chk_recon_case_commission_rate
    CHECK (carrier_commission_rate IS NULL OR (carrier_commission_rate > 0 AND carrier_commission_rate <= 1));
