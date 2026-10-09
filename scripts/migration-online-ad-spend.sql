-- Inversión diaria en publicidad (ventas online) — comparación contra la venta online del día
-- Run manually against PostgreSQL ANTES de desplegar el backend (JPA ddl-auto=validate: sin esta tabla el arranque falla).
-- Idempotente: se puede ejecutar más de una vez.

CREATE TABLE IF NOT EXISTS online_ad_spend (
    id          BIGSERIAL PRIMARY KEY,
    spend_date  DATE NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    notes       VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    CONSTRAINT uq_online_ad_spend_date UNIQUE (spend_date),
    CONSTRAINT chk_online_ad_spend_amount CHECK (amount >= 0)
);

COMMENT ON TABLE online_ad_spend IS 'Inversión en publicidad por día (Q, todas las plataformas) para comparar contra la venta online del día';
COMMENT ON COLUMN online_ad_spend.spend_date IS 'Día de la inversión (fecha Guatemala); un solo registro por día';
COMMENT ON COLUMN online_ad_spend.created_by IS 'users.id de quien capturó el día';
COMMENT ON COLUMN online_ad_spend.updated_by IS 'users.id de quien modificó el día por última vez';
