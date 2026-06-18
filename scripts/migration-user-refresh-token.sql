-- Refresh tokens para renovar JWT sin re-login (app móvil y clientes compatibles).
CREATE TABLE IF NOT EXISTS user_refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    device_label VARCHAR(120),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_refresh_token_hash ON user_refresh_token (token_hash);
CREATE INDEX IF NOT EXISTS idx_user_refresh_token_user_id ON user_refresh_token (user_id);
