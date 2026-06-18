-- FEL — factura electrónica en ventas POS kiosko
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS fel_status VARCHAR(30),
    ADD COLUMN IF NOT EXISTS fel_uuid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS fel_serie VARCHAR(32),
    ADD COLUMN IF NOT EXISTS fel_numero VARCHAR(32),
    ADD COLUMN IF NOT EXISTS fel_error VARCHAR(4000),
    ADD COLUMN IF NOT EXISTS fel_certified_at TIMESTAMP;
