-- TEST ENVIRONMENT FIRST
-- PHASE 2 — run ONLY together with deploying the new code to production.
-- Do not run this while production still executes current main: main inserts
-- one CHARGE per partial and sometimes with production_order_id NULL.
-- This script does not modify ledger rows.
--
-- The SELECT below must return zero rows before the index is created.
-- Clean duplicates by hand first (no cleanup is included here). If any row
-- comes back, stop. The guard below aborts before the CHECK and the index.

-- Orders with more than one non-void CHARGE. Must be empty.
SELECT production_order_id,
       count(*) AS active_charges
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND production_order_id IS NOT NULL
GROUP BY production_order_id
HAVING count(*) > 1
ORDER BY production_order_id;

-- Non-void charges that still have no order. The new CHECK would reject them.
SELECT id, customer_id, status, entry_date, amount
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND production_order_id IS NULL
ORDER BY id;

DO $$
DECLARE
    duplicate_orders integer;
    charges_without_order integer;
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
    SELECT count(*) INTO charges_without_order
    FROM customer_account_entry
    WHERE entry_type = 'CHARGE'
      AND status <> 'VOID'
      AND production_order_id IS NULL;
    IF duplicate_orders > 0 OR charges_without_order > 0 THEN
        RAISE EXCEPTION
            'PHASE 2 aborted: % orders have more than one active CHARGE and % active charges have no order. No ledger rows were modified.',
            duplicate_orders, charges_without_order;
    END IF;
END $$;

ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_charge_order;

ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_charge_order CHECK (
        entry_type <> 'CHARGE' OR status = 'VOID' OR production_order_id IS NOT NULL
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_cae_one_active_charge_per_order
    ON customer_account_entry (production_order_id)
    WHERE entry_type = 'CHARGE' AND status <> 'VOID';
