-- Liquidar un periodo de revisión del libro de ventas en línea desde la Conciliación.
-- 1) recon_batch.ledger_period_id: el lote de liquidación queda asociado al periodo de revisión del libro que se liquidó.
-- 2) online_ledger_log admite el evento LIQUIDATE (queda en la bitácora de la revisión).
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-reconciliation-base.sql y scripts/migration-online-ledger-log.sql. Idempotente.
-- Rollback:
--   ALTER TABLE online_ledger_log DROP CONSTRAINT IF EXISTS ck_online_ledger_log_type;
--   ALTER TABLE online_ledger_log ADD CONSTRAINT ck_online_ledger_log_type
--       CHECK (event_type IN ('OPEN', 'CLOSE', 'REOPEN', 'DELETE', 'EDIT', 'LOCK', 'UNLOCK'));  -- falla si ya hay filas LIQUIDATE
--   DROP INDEX IF EXISTS ix_recon_batch_ledger_period;
--   ALTER TABLE recon_batch DROP COLUMN IF EXISTS ledger_period_id;

ALTER TABLE recon_batch ADD COLUMN IF NOT EXISTS ledger_period_id BIGINT REFERENCES online_ledger_period (id);
CREATE INDEX IF NOT EXISTS ix_recon_batch_ledger_period ON recon_batch (ledger_period_id) WHERE ledger_period_id IS NOT NULL;
COMMENT ON COLUMN recon_batch.ledger_period_id IS 'Periodo de revisión del libro de ventas en línea que se liquidó con este lote (nulo si el lote se armó a mano).';

ALTER TABLE online_ledger_log DROP CONSTRAINT IF EXISTS ck_online_ledger_log_type;
ALTER TABLE online_ledger_log ADD CONSTRAINT ck_online_ledger_log_type
    CHECK (event_type IN ('OPEN', 'CLOSE', 'REOPEN', 'DELETE', 'EDIT', 'LOCK', 'UNLOCK', 'LIQUIDATE'));
