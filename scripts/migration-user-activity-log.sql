-- Registro de actividad de usuarios y última conexión
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP WITHOUT TIME ZONE;

CREATE TABLE IF NOT EXISTS user_activity_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    http_method VARCHAR(10),
    request_path VARCHAR(255),
    ip_address VARCHAR(50),
    user_agent VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_activity_log_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_activity_log_user_created ON user_activity_log (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_last_activity ON users (last_activity_at DESC);
