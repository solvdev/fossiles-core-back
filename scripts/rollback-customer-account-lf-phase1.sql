-- ROLLBACK PHASE 1. Index-revised copy of PR #120 scripts/rollback-customer-account-lf-phase1.sql.
-- Usually NOT needed: current main runs fine with phase 1 in place. Run only if you must remove it, and only
--   * after rollback-customer-account-lf-phase2.sql, and
--   * after the database is back on current main (the #120 binary needs credit_days and CHARGE_ADJUSTMENT).
-- Aborts (nothing changed) if any CHARGE_ADJUSTMENT row exists, VOID ones included: the 5-value CHECK would
-- reject them, and deleting them would change balances. Saves credit_days to a CSV on YOUR machine first.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f rollback-customer-account-lf-phase1.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;

SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'customer' AND column_name = 'credit_days') AS tiene_credit_days \gset
\if :tiene_credit_days
\copy (SELECT id AS customer_id, legacy_code, credit_days FROM customer WHERE credit_days <> 0 ORDER BY id) TO 'credit-days-antes-de-rollback.csv' WITH (FORMAT csv, HEADER)
\endif

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    adjustments bigint;
BEGIN
    IF to_regclass('uq_cae_one_active_charge_per_order') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order') THEN
        RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: primero corre rollback-customer-account-lf-phase2.sql. No se cambio nada.';
    END IF;
    SELECT count(*) INTO adjustments FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT';
    IF adjustments > 0 THEN
        RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: hay % filas CHARGE_ADJUSTMENT (incluidas anuladas). El CHECK de 5 tipos las rechazaria y borrarlas cambia saldos. No se cambio nada.', adjustments;
    END IF;
END $$;

DROP INDEX IF EXISTS uq_cae_one_active_adjustment_per_shipment;
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_adjustment_links;
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_type;
ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_type CHECK (
        entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN')
    );
ALTER TABLE customer DROP CONSTRAINT IF EXISTS chk_customer_credit_days;
ALTER TABLE customer DROP COLUMN IF EXISTS credit_days;
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS fk_customer_account_entry_reassigned_from;
ALTER TABLE customer_account_entry DROP COLUMN IF EXISTS reassigned_from_entry_id;
COMMENT ON COLUMN customer_account_entry.entry_type IS 'CHARGE | PAYMENT | CREDIT_NOTE | OPENING_BALANCE | RETURN';
COMMENT ON COLUMN customer_account_entry.applied_to_entry_id IS 'Cargo CHARGE al que aplica PAYMENT o RETURN';
COMMIT;
