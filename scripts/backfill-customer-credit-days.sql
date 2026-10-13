-- BACKFILL credit_days. Index-revised copy of PR #120 scripts/backfill-customer-credit-days.sql (the PR file is a
-- template with "?" placeholders and is not runnable SQL). Fill the VALUES list with Eduardo's real days.
-- Matches by customer.id (works for the ~40 customers without legacy_code) or by legacy_code.
-- Aborts with nothing changed if a row matches no customer, a customer appears twice, or days are outside 0..60.
-- Needs phase 1 first. Does not touch the ledger.
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;
BEGIN;
SET LOCAL lock_timeout = '5s';
CREATE TEMP TABLE credit_days_input (customer_id bigint, legacy_code varchar(30), credit_days integer) ON COMMIT DROP;
INSERT INTO credit_days_input (customer_id, legacy_code, credit_days) VALUES
    (NULL, 'CB490', 30),   -- example by legacy_code: replace
    (123, NULL, 45);       -- example by customer.id: replace

DO $$
DECLARE
    unmatched integer;
    duplicated integer;
    out_of_range integer;
BEGIN
    SELECT count(*) INTO out_of_range FROM credit_days_input WHERE credit_days IS NULL OR credit_days NOT BETWEEN 0 AND 60;
    SELECT count(*) INTO unmatched
    FROM credit_days_input i
    WHERE NOT EXISTS (
        SELECT 1 FROM customer c
        WHERE (i.customer_id IS NOT NULL AND c.id = i.customer_id)
           OR (i.customer_id IS NULL AND c.legacy_code = i.legacy_code));
    SELECT count(*) INTO duplicated FROM (
        SELECT c.id
        FROM credit_days_input i
        JOIN customer c ON (i.customer_id IS NOT NULL AND c.id = i.customer_id)
                        OR (i.customer_id IS NULL AND c.legacy_code = i.legacy_code)
        GROUP BY c.id
        HAVING count(*) > 1) d;
    IF unmatched > 0 OR duplicated > 0 OR out_of_range > 0 THEN
        RAISE EXCEPTION 'Backfill abortado: % filas sin cliente, % clientes repetidos, % dias fuera de 0..60. No se cambio nada.',
            unmatched, duplicated, out_of_range;
    END IF;
END $$;

UPDATE customer c
SET credit_days = i.credit_days
FROM credit_days_input i
WHERE (i.customer_id IS NOT NULL AND c.id = i.customer_id)
   OR (i.customer_id IS NULL AND c.legacy_code = i.legacy_code);

SELECT count(*) FILTER (WHERE credit_days > 0) AS clientes_con_credito, count(*) AS clientes FROM customer;
COMMIT;
