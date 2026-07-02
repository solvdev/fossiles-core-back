-- Tiers de promoción POS: audiencia + categoría de producto
-- Ejecutar manualmente en PostgreSQL si no usa ddl-auto=update

ALTER TABLE kiosk_promotion_tier
    ADD COLUMN IF NOT EXISTS category_id BIGINT;

ALTER TABLE kiosk_promotion_tier
    DROP CONSTRAINT IF EXISTS uq_kiosk_promotion_tier;

ALTER TABLE kiosk_promotion_tier
    ADD CONSTRAINT uq_kiosk_promotion_tier
        UNIQUE (promotion_id, audience_category, category_id);

CREATE INDEX IF NOT EXISTS idx_kiosk_promotion_tier_category_id
    ON kiosk_promotion_tier (category_id);
