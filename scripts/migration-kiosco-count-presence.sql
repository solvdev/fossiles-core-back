-- Presencia en sesión de conteo físico kiosko (colaboración en vivo).
CREATE TABLE IF NOT EXISTS kiosco_physical_count_presence (
    id BIGSERIAL PRIMARY KEY,
    count_id BIGINT NOT NULL REFERENCES kiosco_physical_count(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_seen_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kiosco_count_presence UNIQUE (count_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_kiosco_count_presence_count_seen
    ON kiosco_physical_count_presence (count_id, last_seen_at DESC);
