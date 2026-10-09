-- PHASE 1 (additive). Index-revised copy of PR #120 scripts/migration-customer-account-lf-phase1.sql.
-- Safe while the database is still served by current main: main never writes CHARGE_ADJUSTMENT (its
-- normalizeEntryType rejects it) and does not map customer.credit_days (inserts get DEFAULT 0).
-- Two databases: run on fosstest first, then on fossilesgt (develop -> fosstest, main -> fossilesgt). Any time
-- before the PR #120 binary is deployed to that database.
-- Changes vs the PR: ON_ERROR_STOP; constraint swap in ONE transaction (no window without a type check) with
-- lock_timeout; CHECKs added NOT VALID and validated afterwards (validation does not block writes); re-runs skip
-- constraints that are already correct; CHARGE_ADJUSTMENT must carry order, shipment and parent charge
-- (otherwise NULL product_shipment_id rows escape the partial unique index); unique index built CONCURRENTLY.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f migration-customer-account-lf-phase1.sql   (never with -1)
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino, current_setting('server_version_num')::int >= 110000 AS pg11_o_mas;

BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    -- 1) entry_type list + CHARGE_ADJUSTMENT (replaced only if the current definition lacks it)
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'customer_account_entry'::regclass
          AND conname = 'chk_customer_account_entry_type'
          AND pg_get_constraintdef(oid) LIKE '%CHARGE_ADJUSTMENT%'
    ) THEN
        ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_type;
        ALTER TABLE customer_account_entry
            ADD CONSTRAINT chk_customer_account_entry_type CHECK (
                entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN', 'CHARGE_ADJUSTMENT')
            ) NOT VALID;
    END IF;

    -- 2) A live CHARGE_ADJUSTMENT always points to its order, its shipment and its CHARGE (what #120 writes).
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'customer_account_entry'::regclass
          AND conname = 'chk_customer_account_entry_adjustment_links'
    ) THEN
        ALTER TABLE customer_account_entry
            ADD CONSTRAINT chk_customer_account_entry_adjustment_links CHECK (
                entry_type <> 'CHARGE_ADJUSTMENT'
                OR status = 'VOID'
                OR (production_order_id IS NOT NULL
                    AND product_shipment_id IS NOT NULL
                    AND applied_to_entry_id IS NOT NULL)
            ) NOT VALID;
    END IF;
END $$;

-- 3) Credit days. Constant DEFAULT: metadata-only on PG 11+, no table rewrite.
ALTER TABLE customer ADD COLUMN IF NOT EXISTS credit_days INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'customer'::regclass AND conname = 'chk_customer_credit_days'
    ) THEN
        ALTER TABLE customer
            ADD CONSTRAINT chk_customer_credit_days CHECK (credit_days BETWEEN 0 AND 60) NOT VALID;
    END IF;
END $$;

COMMENT ON COLUMN customer.credit_days IS
    'Dias de credito del cliente (0-60). El vencimiento se calcula al leer; no hay columna due_date.';
COMMENT ON COLUMN customer_account_entry.entry_type IS
    'CHARGE | PAYMENT | CREDIT_NOTE | OPENING_BALANCE | RETURN | CHARGE_ADJUSTMENT';
COMMENT ON COLUMN customer_account_entry.applied_to_entry_id IS
    'Cargo CHARGE al que aplica PAYMENT, CREDIT_NOTE, RETURN o CHARGE_ADJUSTMENT';

-- 3b) Trace of a row moved by void-and-reassign. Nullable, no default: metadata-only.
--     Keeps the first original charge; the service does not overwrite it.
ALTER TABLE customer_account_entry
    ADD COLUMN IF NOT EXISTS reassigned_from_entry_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'customer_account_entry'::regclass
          AND conname = 'fk_customer_account_entry_reassigned_from'
    ) THEN
        ALTER TABLE customer_account_entry
            ADD CONSTRAINT fk_customer_account_entry_reassigned_from
            FOREIGN KEY (reassigned_from_entry_id) REFERENCES customer_account_entry (id) NOT VALID;
    END IF;
END $$;

COMMENT ON COLUMN customer_account_entry.reassigned_from_entry_id IS
    'Cargo original del que se traslado este movimiento. No se pisa si se traslada otra vez.';
COMMIT;

-- 4) Validate (SHARE UPDATE EXCLUSIVE: reads and writes keep working). No-op if already validated.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE customer_account_entry VALIDATE CONSTRAINT chk_customer_account_entry_type;
ALTER TABLE customer_account_entry VALIDATE CONSTRAINT chk_customer_account_entry_adjustment_links;
ALTER TABLE customer_account_entry VALIDATE CONSTRAINT fk_customer_account_entry_reassigned_from;
ALTER TABLE customer VALIDATE CONSTRAINT chk_customer_credit_days;
COMMIT;

-- 5) One live adjustment per shipment. Outside any transaction; does not block writes.
--    If it fails half-way it leaves an INVALID index: DROP INDEX CONCURRENTLY uq_cae_one_active_adjustment_per_shipment; and re-run.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_cae_one_active_adjustment_per_shipment
    ON customer_account_entry (product_shipment_id)
    WHERE entry_type = 'CHARGE_ADJUSTMENT' AND status <> 'VOID';

-- 6) Checks (read only): every row must say t.
SELECT conname, convalidated
FROM pg_constraint
WHERE conname IN (
    'chk_customer_account_entry_type',
    'chk_customer_account_entry_adjustment_links',
    'chk_customer_credit_days',
    'fk_customer_account_entry_reassigned_from'
)
ORDER BY conname;
SELECT COALESCE(bool_and(i.indisvalid), false) AS indice_ajuste_valido
FROM pg_catalog.pg_index i
WHERE i.indexrelid = to_regclass('uq_cae_one_active_adjustment_per_shipment');
