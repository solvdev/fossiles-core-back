-- PHASE 2. Index-revised copy of PR #120 scripts/migration-customer-account-lf-phase2.sql.
-- Run on a database ONLY when (a) every backend writing to it runs the PR #120 code, and (b) the duplicate-charge
-- cleanup (separate script, not in the PR yet) has run and its balance check passed.
--   fosstest:   right after #120 is deployed to develop.   fossilesgt: right after #120 is promoted to main.
-- Changes vs the PR: ON_ERROR_STOP (the PR file has none, so after the guard's exception psql kept going and
-- could still add the CHECK); guard + CHECK + index in ONE transaction under an explicit lock, so nothing can be
-- inserted between the check and the DDL and an abort leaves the table exactly as it was; lock_timeout;
-- CHECK also covers CHARGE_ADJUSTMENT.
-- Lock: ACCESS EXCLUSIVE on customer_account_entry for the duration of the scan + index build (small table:
-- well under a second). If filas_ledger below is in the millions, use the concurrent variant in the review instead.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f migration-customer-account-lf-phase2.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino, (SELECT count(*) FROM customer_account_entry) AS filas_ledger;

-- Pre-checks (read only). Both must return 0 rows.
SELECT production_order_id, count(*) AS active_charges, array_agg(id ORDER BY id) AS entry_ids
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND production_order_id IS NOT NULL
GROUP BY production_order_id
HAVING count(*) > 1
ORDER BY production_order_id;

SELECT id, customer_id, entry_type, status, entry_date, amount
FROM customer_account_entry
WHERE entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
  AND status <> 'VOID'
  AND production_order_id IS NULL
ORDER BY id;

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    duplicate_orders integer;
    rows_without_order integer;
BEGIN
    SELECT count(*) INTO duplicate_orders FROM (
        SELECT production_order_id
        FROM customer_account_entry
        WHERE entry_type = 'CHARGE'
          AND status <> 'VOID'
          AND production_order_id IS NOT NULL
        GROUP BY production_order_id
        HAVING count(*) > 1
    ) d;
    SELECT count(*) INTO rows_without_order
    FROM customer_account_entry
    WHERE entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
      AND status <> 'VOID'
      AND production_order_id IS NULL;
    IF duplicate_orders > 0 OR rows_without_order > 0 THEN
        RAISE EXCEPTION
            'FASE 2 abortada: % ordenes con mas de un CHARGE activo y % cargos/ajustes activos sin orden. No se cambio nada.',
            duplicate_orders, rows_without_order;
    END IF;
END $$;

ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_charge_order;
ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_charge_order CHECK (
        entry_type NOT IN ('CHARGE', 'CHARGE_ADJUSTMENT') OR status = 'VOID' OR production_order_id IS NOT NULL
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_cae_one_active_charge_per_order
    ON customer_account_entry (production_order_id)
    WHERE entry_type = 'CHARGE' AND status <> 'VOID';

COMMIT;

SELECT to_regclass('uq_cae_one_active_charge_per_order') IS NOT NULL AS indice_creado,
       EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order' AND convalidated) AS check_validado;
