-- Certificación de hoja principal por corte de conteo físico
ALTER TABLE kiosco_physical_count
    ADD COLUMN IF NOT EXISTS main_sheet_certified_by VARCHAR(120),
    ADD COLUMN IF NOT EXISTS main_sheet_reviewed_by VARCHAR(120),
    ADD COLUMN IF NOT EXISTS main_sheet_certified_at TIMESTAMP;
