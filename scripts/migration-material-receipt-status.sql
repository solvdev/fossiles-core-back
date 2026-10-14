BEGIN;
ALTER TABLE material_receipt ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE material_receipt ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(1000);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_material_receipt_status') THEN
        ALTER TABLE material_receipt ADD CONSTRAINT chk_material_receipt_status CHECK (status IN ('ACTIVE', 'CANCELLED'));
    END IF;
END $$;
COMMIT;
