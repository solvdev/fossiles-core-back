-- Promociones POS por línea (Dama / Caballero / Unisex)
-- Ejecutar manualmente en PostgreSQL si no usa ddl-auto=update

CREATE TABLE IF NOT EXISTS kiosk_promotion_tier (
    id BIGSERIAL PRIMARY KEY,
    promotion_id BIGINT NOT NULL REFERENCES kiosk_promotion (id) ON DELETE CASCADE,
    audience_category VARCHAR(20) NOT NULL,
    discount_value NUMERIC(12, 2) NOT NULL,
    CONSTRAINT uq_kiosk_promotion_tier UNIQUE (promotion_id, audience_category)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_promotion_tier_promotion_id
    ON kiosk_promotion_tier (promotion_id);
