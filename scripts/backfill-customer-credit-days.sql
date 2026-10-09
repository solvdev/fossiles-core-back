-- TEST ENVIRONMENT FIRST
-- Template only. Replace each ? . credit_days must be between 0 and 60.
-- Does not touch the ledger.
UPDATE customer SET credit_days = ? WHERE legacy_code = ?;
