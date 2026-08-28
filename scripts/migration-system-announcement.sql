-- Anuncios y alertas del sistema en tiempo real (reinicios, mantenimientos, avisos globales)
CREATE TABLE IF NOT EXISTS system_announcement (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(500) NOT NULL,
    announcement_type VARCHAR(30) NOT NULL DEFAULT 'RESTART_WARNING',
    target_action VARCHAR(50) DEFAULT 'RESTART',
    duration_seconds INT NOT NULL DEFAULT 300,
    created_by_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    dismissed_at TIMESTAMP WITHOUT TIME ZONE,
    dismissed_by_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_system_announcement_active ON system_announcement (is_active, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_system_announcement_created ON system_announcement (created_at DESC);
