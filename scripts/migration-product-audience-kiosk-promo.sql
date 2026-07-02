-- Línea de producto (dama / caballero / unisex) y promociones POS por audiencia
-- Ejecutar manualmente en PostgreSQL si no usa ddl-auto=update

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS audience_category VARCHAR(20) DEFAULT 'UNISEX';

UPDATE product
SET audience_category = 'UNISEX'
WHERE audience_category IS NULL OR TRIM(audience_category) = '';

ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS audience_category VARCHAR(20);
