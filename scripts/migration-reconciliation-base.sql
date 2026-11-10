-- Conciliación de ventas en línea: tablas base (Fase 0). Solo estructura: ningún servicio las usa todavía.
-- Plan: fossiles-core-front/docs/conciliacion-ventas-linea/PLAN.md (secciones 5 y 6).
-- Correr en fosstest y luego en fossilesgt. No toca online_sale ni customer_account_entry.
-- Rollback: scripts/rollback-reconciliation-base.sql
--
-- Todas las tablas llevan prefijo recon_. recon_event y recon_ledger_entry son de SOLO AGREGAR: un trigger
-- rechaza UPDATE y DELETE (levanta excepción; a diferencia de prevent_kiosco_movement_mutation no existe bandera
-- para saltarlo, a propósito). Las correcciones se hacen con movimientos nuevos (reversas), nunca editando.

-- ───────────── Catálogo de vendedores (decisión D4) ─────────────
CREATE TABLE IF NOT EXISTS recon_seller (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,   -- igual, letra por letra, a online_sale.salesperson
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    user_id     BIGINT,                  -- opcional, usuario del sistema
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    CONSTRAINT uq_recon_seller_name UNIQUE (name)
);

-- Los tres vendedores que hoy existen en online_sale.salesperson (las ventas ya traen estos nombres exactos).
INSERT INTO recon_seller (name) VALUES ('Anthony Ixcajo'), ('Eduardo Ramirez'), ('Luisa Marquez')
ON CONFLICT (name) DO NOTHING;

-- ───────────── Archivos importados (idempotencia por hash del archivo) ─────────────
CREATE TABLE IF NOT EXISTS recon_import_file (
    id            BIGSERIAL PRIMARY KEY,
    file_name     VARCHAR(255) NOT NULL,
    sha256        VARCHAR(64)     NOT NULL,
    source_type   VARCHAR(20)  NOT NULL,
    rows_total    INT          NOT NULL DEFAULT 0,
    rows_matched  INT          NOT NULL DEFAULT 0,
    imported_by   BIGINT,
    imported_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_recon_import_file_sha UNIQUE (sha256),
    CONSTRAINT chk_recon_import_source CHECK (source_type IN ('CARRIER', 'BANK', 'CARD', 'CASH', 'MANUAL'))
);

-- ───────────── Lotes de descarga ─────────────
CREATE TABLE IF NOT EXISTS recon_batch (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40)   NOT NULL,
    period_from      DATE          NOT NULL,
    period_to        DATE          NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    idempotency_key  VARCHAR(100),
    total_expected   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_received   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_adjusted   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by       BIGINT,
    closed_at        TIMESTAMP,
    closed_by        BIGINT,
    void_reason      TEXT,
    voided_at        TIMESTAMP,
    voided_by        BIGINT,
    CONSTRAINT uq_recon_batch_code UNIQUE (code),
    CONSTRAINT chk_recon_batch_status CHECK (status IN ('OPEN', 'CLOSED', 'VOID')),
    CONSTRAINT chk_recon_batch_period CHECK (period_to >= period_from),
    CONSTRAINT chk_recon_batch_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_recon_batch_idempotency ON recon_batch (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ───────────── Caso de conciliación: uno por venta/ENVL ─────────────
CREATE TABLE IF NOT EXISTS recon_case (
    id                     BIGSERIAL PRIMARY KEY,
    online_sale_id         BIGINT        NOT NULL REFERENCES online_sale (id),
    origin                 VARCHAR(10)   NOT NULL DEFAULT 'NORMAL',    -- NORMAL o HISTORICO (descarga por tandas)
    sale_date              DATE          NOT NULL,                     -- foto de online_sale.sale_date
    sale_number            VARCHAR(30),                                -- foto de online_sale.sale_number (no es único)
    shipment_number        VARCHAR(30),
    guide_number           VARCHAR(100),
    carrier                VARCHAR(50),
    payment_method         VARCHAR(50),
    salesperson            VARCHAR(100),
    seller_id              BIGINT REFERENCES recon_seller (id),
    expected_amount        NUMERIC(12, 2) NOT NULL,                    -- foto de online_sale.total_amount
    received_amount        NUMERIC(12, 2) NOT NULL DEFAULT 0,          -- suma de aplicaciones activas (se recalcula)
    difference             NUMERIC(12, 2) NOT NULL DEFAULT 0,          -- recibido - cobrado (se recalcula)
    authorized_adjustment  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    pending_balance        NUMERIC(12, 2) NOT NULL DEFAULT 0,          -- cobrado - recibido - ajuste (se recalcula)
    status                 VARCHAR(30)   NOT NULL DEFAULT 'PENDIENTE_REVISION',
    dispatched_at          TIMESTAMP,                                  -- foto de online_sale.dispatched_at
    source_updated_at      TIMESTAMP,                                  -- online_sale.updated_at al crear el caso
    manual_reconciled      BOOLEAN       NOT NULL DEFAULT FALSE,       -- conciliado a mano con advertencias aceptadas
    excluded_reason        VARCHAR(300),
    problem_type           VARCHAR(40),                                -- problema principal abierto (se recalcula)
    critical_count         INT           NOT NULL DEFAULT 0,           -- reglas críticas que fallan (se recalcula)
    warning_count          INT           NOT NULL DEFAULT 0,           -- advertencias que fallan (se recalcula)
    batch_id               BIGINT REFERENCES recon_batch (id),
    closed_at              TIMESTAMP,
    version                INT           NOT NULL DEFAULT 0,           -- bloqueo optimista
    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by             BIGINT,
    updated_at             TIMESTAMP,
    updated_by             BIGINT,
    CONSTRAINT uq_recon_case_sale UNIQUE (online_sale_id),
    CONSTRAINT chk_recon_case_origin CHECK (origin IN ('NORMAL', 'HISTORICO')),
    CONSTRAINT chk_recon_case_status CHECK (status IN (
        'PENDIENTE_REVISION', 'CONCILIADO_AUTO', 'CONCILIADO_MANUAL', 'CON_DIFERENCIAS',
        'INFORMACION_INCOMPLETA', 'POSIBLE_DUPLICADO', 'REQUIERE_AUTORIZACION',
        'CONCILIADO_CON_AJUSTE', 'EXCLUIDO')),
    CONSTRAINT chk_recon_case_closed CHECK (closed_at IS NULL OR batch_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_recon_case_status ON recon_case (status);
CREATE INDEX IF NOT EXISTS idx_recon_case_sale_date ON recon_case (sale_date);
CREATE INDEX IF NOT EXISTS idx_recon_case_problem ON recon_case (problem_type) WHERE problem_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_case_batch ON recon_case (batch_id);
CREATE INDEX IF NOT EXISTS idx_recon_case_shipment ON recon_case (shipment_number);
CREATE INDEX IF NOT EXISTS idx_recon_case_guide ON recon_case (guide_number) WHERE guide_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_case_seller ON recon_case (seller_id);

-- ───────────── Fondos recibidos (liquidaciones, extractos, reportes de tarjeta, captura manual) ─────────────
CREATE TABLE IF NOT EXISTS recon_receipt (
    id                    BIGSERIAL PRIMARY KEY,
    import_file_id        BIGINT REFERENCES recon_import_file (id),
    source_type           VARCHAR(20)   NOT NULL,
    row_hash              VARCHAR(64),                                    -- idempotencia por fila importada
    carrier               VARCHAR(50),
    shipment_number       VARCHAR(30),                                 -- ENVL que reporta la fuente, si lo trae
    guide_number          VARCHAR(100),
    reference             VARCHAR(100),
    authorization_number  VARCHAR(100),
    provider_txn_id       VARCHAR(100),
    received_date         DATE,
    amount                NUMERIC(12, 2) NOT NULL,
    shipping_amount       NUMERIC(12, 2),                              -- envío que reporta la fuente (regla R12)
    third_party_fee       NUMERIC(12, 2),                              -- opcional, sin uso en reglas (C10)
    raw_payload           TEXT,
    status                VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    void_reason           TEXT,
    voided_at             TIMESTAMP,
    voided_by             BIGINT,
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by            BIGINT,
    CONSTRAINT chk_recon_receipt_source CHECK (source_type IN ('CARRIER', 'BANK', 'CARD', 'CASH', 'MANUAL')),
    CONSTRAINT chk_recon_receipt_status CHECK (status IN ('ACTIVE', 'VOID')),
    CONSTRAINT chk_recon_receipt_amount CHECK (amount >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_recon_receipt_row_hash ON recon_receipt (row_hash) WHERE row_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_receipt_guide ON recon_receipt (guide_number) WHERE guide_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_receipt_reference ON recon_receipt (reference) WHERE reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_receipt_shipment ON recon_receipt (shipment_number) WHERE shipment_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_recon_receipt_file ON recon_receipt (import_file_id);

-- Un fondo puede repartirse entre varios casos y un caso puede recibir varios fondos.
CREATE TABLE IF NOT EXISTS recon_receipt_application (
    id              BIGSERIAL PRIMARY KEY,
    case_id         BIGINT        NOT NULL REFERENCES recon_case (id),
    receipt_id      BIGINT        NOT NULL REFERENCES recon_receipt (id),
    amount_applied  NUMERIC(12, 2) NOT NULL,
    match_method    VARCHAR(15)   NOT NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    applied_by      BIGINT,
    applied_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    void_reason     TEXT,
    voided_at       TIMESTAMP,
    voided_by       BIGINT,
    CONSTRAINT chk_recon_application_amount CHECK (amount_applied > 0),
    CONSTRAINT chk_recon_application_method CHECK (match_method IN ('GUIDE', 'REFERENCE', 'AUTH', 'ENVL', 'MANUAL')),
    CONSTRAINT chk_recon_application_status CHECK (status IN ('ACTIVE', 'VOID'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_recon_application_active ON recon_receipt_application (case_id, receipt_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_recon_application_case ON recon_receipt_application (case_id);
CREATE INDEX IF NOT EXISTS idx_recon_application_receipt ON recon_receipt_application (receipt_id);

-- ───────────── Resultado de cada regla de validación ─────────────
CREATE TABLE IF NOT EXISTS recon_check_result (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             BIGINT       NOT NULL REFERENCES recon_case (id),
    run_id              BIGINT       NOT NULL,
    rule_code           VARCHAR(10)  NOT NULL,
    severity            VARCHAR(10)  NOT NULL,
    outcome             VARCHAR(5)   NOT NULL,
    expected_value      VARCHAR(200),
    found_value         VARCHAR(200),
    message             VARCHAR(500),
    recommended_action  VARCHAR(300),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_recon_check_severity CHECK (severity IN ('CRITICAL', 'WARNING')),
    CONSTRAINT chk_recon_check_outcome CHECK (outcome IN ('PASS', 'FAIL', 'NA'))
);
CREATE INDEX IF NOT EXISTS idx_recon_check_case_run ON recon_check_result (case_id, run_id);

-- ───────────── Diferencias y ajustes autorizados ─────────────
CREATE TABLE IF NOT EXISTS recon_difference (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT        NOT NULL REFERENCES recon_case (id),
    diff_type           VARCHAR(40)   NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    reason         TEXT,
    proposed_by    BIGINT,
    proposed_at    TIMESTAMP,
    authorized_by  BIGINT,          -- puede ser la misma persona que propuso (decisión del negocio, C24)
    authorized_at  TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_recon_difference_type CHECK (diff_type IN (
        'MONTO_INCORRECTO', 'REFERENCIA_NO_ENCONTRADA', 'ENVIO_SIN_VENTA', 'VENTA_SIN_ENVIO',
        'PAGO_DUPLICADO', 'FECHA_INCONSISTENTE', 'COMISION_INCORRECTA', 'DATOS_INSUFICIENTES',
        'PENDIENTE_AUTORIZACION')),
    CONSTRAINT chk_recon_difference_status CHECK (status IN ('OPEN', 'PROPOSED', 'AUTHORIZED', 'REJECTED', 'CORRECTED')),
    CONSTRAINT chk_recon_difference_reason CHECK (status NOT IN ('PROPOSED', 'AUTHORIZED') OR length(btrim(coalesce(reason, ''))) >= 15)
);
CREATE INDEX IF NOT EXISTS idx_recon_difference_case ON recon_difference (case_id);
CREATE INDEX IF NOT EXISTS idx_recon_difference_status ON recon_difference (status);

-- ───────────── Comisión del vendedor: 2% del neto sin envío, se congela al cerrar el mes ─────────────
CREATE TABLE IF NOT EXISTS recon_commission_line (
    id                 BIGSERIAL PRIMARY KEY,
    case_id            BIGINT        NOT NULL REFERENCES recon_case (id),
    seller_id          BIGINT        NOT NULL REFERENCES recon_seller (id),
    month              VARCHAR(7)       NOT NULL,                         -- AAAA-MM
    base_amount        NUMERIC(12, 2) NOT NULL,
    rate               NUMERIC(6, 4) NOT NULL,
    commission_amount  NUMERIC(12, 2) NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'CALCULADA',
    locked_at          TIMESTAMP,
    locked_by          BIGINT,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_recon_commission_case UNIQUE (case_id),
    CONSTRAINT chk_recon_commission_status CHECK (status IN ('CALCULADA', 'ANULADA_DEVOLUCION', 'CONGELADA')),
    CONSTRAINT chk_recon_commission_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT chk_recon_commission_locked CHECK (status <> 'CONGELADA' OR locked_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_recon_commission_month ON recon_commission_line (month, seller_id);

-- ───────────── Descargas: archivo generado al cerrar un lote y sus líneas congeladas ─────────────
CREATE TABLE IF NOT EXISTS recon_export (
    id           BIGSERIAL PRIMARY KEY,
    batch_id     BIGINT      NOT NULL REFERENCES recon_batch (id),
    file_sha256  VARCHAR(64)    NOT NULL,
    row_count    INT         NOT NULL,
    kind         VARCHAR(30) NOT NULL DEFAULT 'CIERRE',
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_recon_export_batch ON recon_export (batch_id);

CREATE TABLE IF NOT EXISTS recon_export_line (
    export_id  BIGINT NOT NULL REFERENCES recon_export (id),
    case_id    BIGINT NOT NULL REFERENCES recon_case (id),
    PRIMARY KEY (export_id, case_id)
);

-- ───────────── Libro de descargas (decisión D13): cada caso es su propia cuenta ─────────────
CREATE TABLE IF NOT EXISTS recon_ledger_entry (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             BIGINT        NOT NULL REFERENCES recon_case (id),
    batch_id            BIGINT REFERENCES recon_batch (id),
    customer_code       VARCHAR(50),                                   -- opcional: código del cliente en SAE
    entry_type          VARCHAR(20)   NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    reverses_entry_id   BIGINT REFERENCES recon_ledger_entry (id),
    idempotency_key     VARCHAR(100)  NOT NULL,
    note                TEXT,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by          BIGINT,
    CONSTRAINT uq_recon_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_recon_ledger_type CHECK (entry_type IN ('CARGO', 'DESCARGA', 'AJUSTE_AUTORIZADO', 'REVERSA')),
    CONSTRAINT chk_recon_ledger_amount CHECK (amount >= 0),
    CONSTRAINT chk_recon_ledger_reversal CHECK ((entry_type = 'REVERSA') = (reverses_entry_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_recon_ledger_case ON recon_ledger_entry (case_id);
CREATE INDEX IF NOT EXISTS idx_recon_ledger_batch ON recon_ledger_entry (batch_id);

-- ───────────── Historial de acciones (solo agregar) ─────────────
CREATE TABLE IF NOT EXISTS recon_event (
    id           BIGSERIAL PRIMARY KEY,
    case_id      BIGINT,                 -- sin FK a propósito: el historial sobrevive a cualquier cambio de casos
    batch_id     BIGINT,
    event_type   VARCHAR(40) NOT NULL,
    from_status  VARCHAR(30),
    to_status    VARCHAR(30),
    payload      TEXT,
    actor_id     BIGINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_recon_event_case ON recon_event (case_id, created_at);
CREATE INDEX IF NOT EXISTS idx_recon_event_batch ON recon_event (batch_id);

-- ───────────── Solo agregar: rechaza UPDATE y DELETE (sin bandera para saltarlo) ─────────────
CREATE OR REPLACE FUNCTION recon_forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% es de solo agregar: no se permite % (corrija con un movimiento nuevo)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_recon_event_append_only ON recon_event;
CREATE TRIGGER trg_recon_event_append_only
    BEFORE UPDATE OR DELETE ON recon_event
    FOR EACH ROW EXECUTE FUNCTION recon_forbid_change();

DROP TRIGGER IF EXISTS trg_recon_ledger_append_only ON recon_ledger_entry;
CREATE TRIGGER trg_recon_ledger_append_only
    BEFORE UPDATE OR DELETE ON recon_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION recon_forbid_change();

-- TRUNCATE también queda prohibido.
DROP TRIGGER IF EXISTS trg_recon_event_no_truncate ON recon_event;
CREATE TRIGGER trg_recon_event_no_truncate
    BEFORE TRUNCATE ON recon_event
    FOR EACH STATEMENT EXECUTE FUNCTION recon_forbid_change();

DROP TRIGGER IF EXISTS trg_recon_ledger_no_truncate ON recon_ledger_entry;
CREATE TRIGGER trg_recon_ledger_no_truncate
    BEFORE TRUNCATE ON recon_ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION recon_forbid_change();
