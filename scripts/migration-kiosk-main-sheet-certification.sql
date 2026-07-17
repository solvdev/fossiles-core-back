-- Certificación de hoja principal por corte de conteo físico
ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS main_sheet_certified_by VARCHAR(120),
    ADD COLUMN IF NOT EXISTS main_sheet_reviewed_by VARCHAR(120),
    ADD COLUMN IF NOT EXISTS main_sheet_certified_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS main_sheet_inventory_from DATE,
    ADD COLUMN IF NOT EXISTS main_sheet_inventory_to DATE,
    ADD COLUMN IF NOT EXISTS main_sheet_sales_from DATE,
    ADD COLUMN IF NOT EXISTS main_sheet_sales_to DATE;
