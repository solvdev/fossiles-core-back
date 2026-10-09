-- ROLLBACK PHASE 1. Index-revised copy of PR #120 scripts/rollback-customer-account-lf-phase1.sql.
-- Usually NOT needed: current main runs fine with phase 1 in place. Run only if you must remove it, and only
--   * after rollback-customer-account-lf-phase2.sql, and
--   * after the database is back on current main (the #120 binary needs credit_days and CHARGE_ADJUSTMENT).
-- Aborts (nothing changed) if phase 2 is still applied: that check runs before any re-link reversal.
-- Otherwise it reverses cleanup re-links of adjustments marked LIMPIEZA-CXC-2026-10 cargo:<old id>
-- and commits that reversal. If CHARGE_ADJUSTMENT rows remain, or any reassigned_from_entry_id is
-- still set, the phase-1 drop then aborts and the message says how many re-links were reversed.
-- Saves credit_days to a CSV on YOUR machine first. Runs cleanly when neither of those rows exists.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f rollback-customer-account-lf-phase1.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;

\set reenlaces_revertidos 0

-- Phase 2 still in place: stop before touching any row.
DO $$
BEGIN
    IF to_regclass('uq_cae_one_active_charge_per_order') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order') THEN
        RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: primero corre rollback-customer-account-lf-phase2.sql. No se cambio nada.';
    END IF;
END $$;

-- Reverse cleanup adjustment re-links. Own transaction so the link is restored even when
-- the phase-1 drop below aborts because CHARGE_ADJUSTMENT rows remain.
BEGIN;
SET LOCAL lock_timeout = '5s';
WITH upd AS (
    UPDATE customer_account_entry
    SET applied_to_entry_id = (regexp_match(description, E'\n?LIMPIEZA-CXC-2026-10 cargo:([0-9]+)'))[1]::bigint,
        reassigned_from_entry_id = CASE
            WHEN reassigned_from_entry_id::text = (regexp_match(description, E'\n?LIMPIEZA-CXC-2026-10 cargo:([0-9]+)'))[1]
                THEN NULL
            ELSE reassigned_from_entry_id
        END,
        description = NULLIF(
            btrim(regexp_replace(description, E'\n?LIMPIEZA-CXC-2026-10 cargo:[0-9]+', '', 'g')),
            '')
    WHERE upper(entry_type) = 'CHARGE_ADJUSTMENT'
      AND description ~ 'LIMPIEZA-CXC-2026-10 cargo:[0-9]+'
    RETURNING 1
)
SELECT count(*) AS reenlaces_revertidos FROM upd \gset
COMMIT;

SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'customer' AND column_name = 'credit_days') AS tiene_credit_days \gset
\if :tiene_credit_days
\copy (SELECT id AS customer_id, legacy_code, credit_days FROM customer WHERE credit_days <> 0 ORDER BY id) TO 'credit-days-antes-de-rollback.csv' WITH (FORMAT csv, HEADER)
\endif

SELECT set_config('limpieza.reenlaces_revertidos', :'reenlaces_revertidos', false);

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    adjustments bigint;
    traced bigint;
    reverted bigint := current_setting('limpieza.reenlaces_revertidos')::bigint;
BEGIN
    IF to_regclass('uq_cae_one_active_charge_per_order') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order') THEN
        RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: primero corre rollback-customer-account-lf-phase2.sql. Se revirtieron % reenlaces de limpieza.',
            reverted;
    END IF;
    SELECT count(*) INTO adjustments FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT';
    IF adjustments > 0 THEN
        RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: hay % filas CHARGE_ADJUSTMENT (incluidas anuladas). El CHECK de 5 tipos las rechazaria y borrarlas cambia saldos. Se revirtieron % reenlaces de limpieza.',
            adjustments, reverted;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'customer_account_entry'
          AND column_name = 'reassigned_from_entry_id'
    ) THEN
        EXECUTE 'SELECT count(*) FROM customer_account_entry WHERE reassigned_from_entry_id IS NOT NULL' INTO traced;
        IF traced > 0 THEN
            RAISE EXCEPTION 'ROLLBACK FASE 1 abortado: hay % filas con reassigned_from_entry_id. Borrar la columna pierde el rastro del traslado. Se revirtieron % reenlaces de limpieza.',
                traced, reverted;
        END IF;
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
