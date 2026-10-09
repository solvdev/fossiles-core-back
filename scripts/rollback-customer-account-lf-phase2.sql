-- TEST ENVIRONMENT FIRST
-- Rollback of phase 2 only. Run together with reverting production to current main.
-- Does not delete ledger rows, credit_days, or the CHARGE_ADJUSTMENT type (phase 1).

DROP INDEX IF EXISTS uq_cae_one_active_charge_per_order;

ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_charge_order;
