-- Conciliación de ventas en línea: envío real y porcentaje de comisión por venta (editables en la pantalla de liquidación).
-- Solo agrega dos columnas nulas a recon_case; no toca datos ni reglas de cobro. Idempotente.
-- Correr en fosstest y luego en fossilesgt, ANTES de desplegar el back (ddl-auto=validate).
-- Rollback: scripts/rollback-reconciliation-shipping-commission.sql
--
--   real_shipping            lo que de verdad costó el envío (se compara con el envío cobrado al cliente; no cambia el cuadre)
--   carrier_commission_rate  3%, 3.5% o 4% elegido por la persona para ventas que no son Forza ni Guatex.
--                            En Forza y Guatex el 3.5% es fijo y se calcula sin guardarlo aquí.
-- La comisión en quetzales no se guarda: es expected_amount (total con envío cobrado) × el porcentaje.

ALTER TABLE recon_case
    ADD COLUMN IF NOT EXISTS real_shipping NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS carrier_commission_rate NUMERIC(5, 4);

ALTER TABLE recon_case DROP CONSTRAINT IF EXISTS chk_recon_case_real_shipping;
ALTER TABLE recon_case ADD CONSTRAINT chk_recon_case_real_shipping CHECK (real_shipping IS NULL OR real_shipping >= 0);

ALTER TABLE recon_case DROP CONSTRAINT IF EXISTS chk_recon_case_commission_rate;
ALTER TABLE recon_case ADD CONSTRAINT chk_recon_case_commission_rate
    CHECK (carrier_commission_rate IS NULL OR carrier_commission_rate IN (0.0300, 0.0350, 0.0400));
