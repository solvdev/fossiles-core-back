-- TEST ENVIRONMENT FIRST
-- PHASE 1 — additive, safe while production still runs current main.
-- Test and production share ONE database. Current main still creates one CHARGE
-- per partial shipment. This script only widens the type check, adds credit_days,
-- and indexes a type main never inserts. It does not modify ledger rows.
-- Run any time BEFORE merging/deploying the new code.
-- Hibernate ddl-auto=validate: customer.credit_days must exist before the new
-- entity mapping is deployed.
--
-- Do NOT run phase 2 (migration-customer-account-lf-phase2.sql) until the new
-- code is deployed. Phase 2 would reject the charges main still inserts.

-- 1) Allow CHARGE_ADJUSTMENT. Existing rows stay valid.
ALTER TABLE customer_account_entry DROP CONSTRAINT IF EXISTS chk_customer_account_entry_type;

ALTER TABLE customer_account_entry
    ADD CONSTRAINT chk_customer_account_entry_type CHECK (
        entry_type IN (
            'CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN', 'CHARGE_ADJUSTMENT'
        )
    );

-- 2) Credit days. DEFAULT 0 keeps existing customers valid. Main ignores the column.
ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS credit_days INTEGER NOT NULL DEFAULT 0;

ALTER TABLE customer DROP CONSTRAINT IF EXISTS chk_customer_credit_days;

ALTER TABLE customer
    ADD CONSTRAINT chk_customer_credit_days CHECK (credit_days BETWEEN 0 AND 60);

-- 3) At most one active shipping adjustment per partial shipment.
--    No CHARGE_ADJUSTMENT rows exist yet, so the index is empty.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cae_one_active_adjustment_per_shipment
    ON customer_account_entry (product_shipment_id)
    WHERE entry_type = 'CHARGE_ADJUSTMENT' AND status <> 'VOID';

COMMENT ON COLUMN customer_account_entry.applied_to_entry_id IS
    'Cargo CHARGE al que aplica PAYMENT, CREDIT_NOTE, RETURN o CHARGE_ADJUSTMENT';

-- Read-only. Run before and after; totals must match because this script
-- does not change ledger rows. Same query: scripts/audit-customer-account-balance-per-customer.sql
SELECT c.id,
       c.legacy_code,
       c.name,
       COALESCE(SUM(CASE
           WHEN e.entry_type IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT') THEN e.amount
           ELSE 0 END), 0)
       - COALESCE(SUM(CASE
           WHEN e.entry_type IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN
               CASE
                   WHEN e.gross_collected_amount IS NOT NULL AND e.gross_collected_amount > 0
                       THEN e.gross_collected_amount
                   WHEN e.payment_discount_amount IS NOT NULL AND e.payment_discount_amount > 0
                       THEN e.amount + e.payment_discount_amount
                   ELSE e.amount
               END
           ELSE 0 END), 0) AS balance
FROM customer c
LEFT JOIN customer_account_entry e
    ON e.customer_id = c.id AND e.status = 'ACTIVE'
GROUP BY c.id, c.legacy_code, c.name
ORDER BY c.id;
