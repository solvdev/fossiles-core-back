-- TEST ENVIRONMENT FIRST
-- Rollback of phase 1 only.
-- Restoring the five-value type check is valid ONLY after every CHARGE_ADJUSTMENT
-- row has been removed. This script does not delete ledger rows.
-- Dropping credit_days discards the stored days.

DROP INDEX IF EXISTS uq_cae_one_active_adjustment_per_shipment;

ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_type;

ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_type CHECK (
        entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN')
    );

ALTER TABLE customer DROP CONSTRAINT IF EXISTS chk_customer_credit_days;

ALTER TABLE customer DROP COLUMN IF EXISTS credit_days;

COMMENT ON COLUMN customer_account_entry.applied_to_entry_id IS
    'Cargo CHARGE al que aplica PAYMENT o RETURN';
