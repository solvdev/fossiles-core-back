-- Libro de ventas en línea: periodos de revisión (apertura y cierre por rango de fechas o mes).
-- Abrir = Contabilidad empieza a revisar ese rango. Cerrar = se bloquean todas las líneas del rango y ya no se
-- modifican (también las ventas que se registren después con fecha dentro del periodo). Reabrir: solo administradores;
-- desbloquea solo lo que bloqueó el cierre (lo bloqueado a mano antes sigue bloqueado).
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-online-sale-accounting.sql y -lock. Idempotente.
-- Rollback:
--   ALTER TABLE online_sale_accounting DROP COLUMN IF EXISTS locked_period_id;
--   DROP TABLE IF EXISTS online_ledger_period;

CREATE TABLE IF NOT EXISTS online_ledger_period (
    id               BIGSERIAL PRIMARY KEY,
    date_from        DATE         NOT NULL,
    date_to          DATE         NOT NULL,
    label            VARCHAR(100) NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    note             VARCHAR(500),
    opened_at        TIMESTAMP    NOT NULL,
    opened_by        BIGINT,
    opened_by_name   VARCHAR(150),
    closed_at        TIMESTAMP,
    closed_by        BIGINT,
    closed_by_name   VARCHAR(150),
    reopened_at      TIMESTAMP,
    reopened_by_name VARCHAR(150),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT ck_online_ledger_period_range CHECK (date_from <= date_to),
    CONSTRAINT ck_online_ledger_period_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS ix_online_ledger_period_dates ON online_ledger_period (date_from, date_to);

ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS locked_period_id BIGINT;
CREATE INDEX IF NOT EXISTS ix_online_sale_accounting_locked_period ON online_sale_accounting (locked_period_id);

COMMENT ON TABLE online_ledger_period IS 'Periodos de revisión del libro de ventas en línea. CLOSED = el rango está bloqueado.';
COMMENT ON COLUMN online_sale_accounting.locked_period_id IS 'Periodo cuyo cierre bloqueó la línea (al reabrir se desbloquean solo estas). NULL = bloqueo manual o sin bloqueo.';
