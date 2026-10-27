-- Deshace migration-reconciliation-shipping-commission.sql. Borra lo que se haya escrito en esas dos columnas.
ALTER TABLE recon_case DROP CONSTRAINT IF EXISTS chk_recon_case_real_shipping;
ALTER TABLE recon_case DROP CONSTRAINT IF EXISTS chk_recon_case_commission_rate;
ALTER TABLE recon_case DROP COLUMN IF EXISTS real_shipping;
ALTER TABLE recon_case DROP COLUMN IF EXISTS carrier_commission_rate;
