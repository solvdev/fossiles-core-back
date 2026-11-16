-- Libro de ventas en línea: bitácora de las revisiones (qué se hizo, quién y cuándo).
-- Una fila por cada evento: abrir / cerrar / reabrir / borrar un periodo, cada celda que se cambia (valor anterior y
-- nuevo) y cada línea que se bloquea o desbloquea a mano. Solo registra desde que se despliega; lo anterior no existe.
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-online-ledger-period.sql. Idempotente.
-- Rollback:
--   DROP TABLE IF EXISTS online_ledger_log;

CREATE TABLE IF NOT EXISTS online_ledger_log (
    id           BIGSERIAL PRIMARY KEY,
    period_id    BIGINT,
    period_label VARCHAR(100),
    sale_id      BIGINT,
    sale_number  VARCHAR(30),
    event_type   VARCHAR(10)  NOT NULL,
    field_key    VARCHAR(60),
    field_label  VARCHAR(80),
    old_value    VARCHAR(1000),
    new_value    VARCHAR(1000),
    detail       VARCHAR(500),
    user_id      BIGINT,
    user_name    VARCHAR(150),
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT ck_online_ledger_log_type CHECK (event_type IN ('OPEN', 'CLOSE', 'REOPEN', 'DELETE', 'EDIT', 'LOCK', 'UNLOCK'))
);

CREATE INDEX IF NOT EXISTS ix_online_ledger_log_period ON online_ledger_log (period_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_online_ledger_log_sale ON online_ledger_log (sale_id);

COMMENT ON TABLE online_ledger_log IS 'Bitácora del libro de ventas en línea: eventos de periodos y cambios de celdas (anterior → nuevo).';
