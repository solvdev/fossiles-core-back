-- Meta de ventas mensual por kiosko (histórica por año/mes)

CREATE TABLE IF NOT EXISTS kiosk_monthly_goal (
    id                   BIGSERIAL PRIMARY KEY,
    kiosk_location_id    BIGINT NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    goal_year            INTEGER NOT NULL,
    goal_month           INTEGER NOT NULL,
    goal_amount          NUMERIC(12, 2) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by_user_id   BIGINT NOT NULL,
    updated_at           TIMESTAMP,
    updated_by_user_id   BIGINT,
    CONSTRAINT chk_kiosk_monthly_goal_month CHECK (goal_month BETWEEN 1 AND 12),
    CONSTRAINT chk_kiosk_monthly_goal_amount CHECK (goal_amount >= 0),
    CONSTRAINT uq_kiosk_monthly_goal_period UNIQUE (kiosk_location_id, goal_year, goal_month)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_monthly_goal_period
    ON kiosk_monthly_goal (goal_year, goal_month);

COMMENT ON TABLE kiosk_monthly_goal IS 'Meta de ventas mensual por kiosko (Q), histórica por año/mes.';
