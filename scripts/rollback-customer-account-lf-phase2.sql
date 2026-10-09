-- ROLLBACK PHASE 2. Index-revised copy. Run only after the database is back on code that needs it gone
-- (i.e. current main again). Removes the index and the CHECK; no ledger rows are touched. Idempotent.
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;
BEGIN;
SET LOCAL lock_timeout = '5s';
DROP INDEX IF EXISTS uq_cae_one_active_charge_per_order;
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_charge_order;
COMMIT;
