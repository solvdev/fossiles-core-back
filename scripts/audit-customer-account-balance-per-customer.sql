-- TEST ENVIRONMENT FIRST
-- Read-only balance per customer. Run before and after phase 1.
-- Phase 1 does not change ledger rows, so the two results must match.
-- CHARGE_ADJUSTMENT is included so the same query is valid before (no such rows) and after.
-- No customer names. Identity is customer_id (legacy_code is the same optional key as the snapshot).

SELECT c.id AS customer_id,
       c.legacy_code,
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
GROUP BY c.id, c.legacy_code
ORDER BY c.id;
