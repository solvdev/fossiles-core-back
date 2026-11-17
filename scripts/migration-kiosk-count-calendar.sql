-- Calendario de visitas de conteo físico a kioscos (Contabilidad > Calendario).
-- Ejecutar ANTES de desplegar el back. Es idempotente.
--
-- El sistema reparte una visita por kiosco al mes, una por día por supervisora, agrupando por región y con
-- fechas aleatorias para que las encargadas no sepan cuándo llega la supervisora.

CREATE TABLE IF NOT EXISTS kiosk_count_visit (
    id                  BIGSERIAL PRIMARY KEY,
    visit_month         CHAR(7)      NOT NULL,                 -- 'YYYY-MM'
    visit_date          DATE         NOT NULL,
    location_id         BIGINT       NOT NULL REFERENCES locations (id),
    supervisor_user_id  BIGINT       NOT NULL REFERENCES users (id),
    region              VARCHAR(30)  NOT NULL,
    status              VARCHAR(12)  NOT NULL DEFAULT 'PLANNED', -- PLANNED | DONE | CANCELLED
    day_seq             INT          NOT NULL DEFAULT 1,         -- día de la visita (1 de 2, 2 de 2...)
    day_total           INT          NOT NULL DEFAULT 1,
    manual              BOOLEAN      NOT NULL DEFAULT FALSE,     -- movida o creada a mano: no se regenera
    note                VARCHAR(500),
    generated_by        BIGINT,
    generated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_kiosk_count_visit_status CHECK (status IN ('PLANNED', 'DONE', 'CANCELLED'))
);

-- Tipo de visita: REGULAR (la del mes) o REVISIT (volver a ir; no sustituye la visita regular del mes).
ALTER TABLE kiosk_count_visit ADD COLUMN IF NOT EXISTS kind VARCHAR(10) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE kiosk_count_visit DROP CONSTRAINT IF EXISTS chk_kiosk_count_visit_kind;
ALTER TABLE kiosk_count_visit ADD CONSTRAINT chk_kiosk_count_visit_kind CHECK (kind IN ('REGULAR', 'REVISIT'));

-- Semanas ya generadas por supervisora (el calendario se genera de lunes a viernes, una semana a la vez).
CREATE TABLE IF NOT EXISTS kiosk_count_week (
    week_start          DATE   NOT NULL,                -- lunes
    supervisor_user_id  BIGINT NOT NULL REFERENCES users (id),
    generated_by        BIGINT,
    generated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (week_start, supervisor_user_id)
);

-- Una visita por día por supervisora (las canceladas no cuentan).
CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosk_count_visit_supervisor_day
    ON kiosk_count_visit (supervisor_user_id, visit_date) WHERE status <> 'CANCELLED';
CREATE INDEX IF NOT EXISTS idx_kiosk_count_visit_month ON kiosk_count_visit (visit_month);
CREATE INDEX IF NOT EXISTS idx_kiosk_count_visit_location ON kiosk_count_visit (location_id, visit_date);

-- Días en que no se programan visitas además de fines de semana y feriados nacionales (que el sistema ya conoce).
CREATE TABLE IF NOT EXISTS kiosk_count_blocked_day (
    blocked_date        DATE PRIMARY KEY,
    reason              VARCHAR(200) NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Reglas propias de cada kiosco (si no hay fila: 1 visita al mes, de 1 día, cualquier día hábil).
--   visits_per_month: Miraflores II se visita cada 15 días (2 al mes).
--   visit_days:       días seguidos que dura cada visita (Entrecueros y Celajes: 2).
--   allowed_weekdays: días de la semana permitidos, ej. 'MON,TUE,WED' (Naranjo Mall); NULL = todos.
CREATE TABLE IF NOT EXISTS kiosk_count_visit_config (
    location_id         BIGINT PRIMARY KEY REFERENCES locations (id) ON DELETE CASCADE,
    visits_per_month    INT NOT NULL DEFAULT 1,
    visit_days          INT NOT NULL DEFAULT 1,
    allowed_weekdays    VARCHAR(40),
    CONSTRAINT chk_kiosk_count_visits_per_month CHECK (visits_per_month BETWEEN 1 AND 4),
    CONSTRAINT chk_kiosk_count_visit_days CHECK (visit_days BETWEEN 1 AND 5)
);

INSERT INTO kiosk_count_visit_config (location_id, visits_per_month, visit_days, allowed_weekdays)
SELECT id,
       CASE WHEN code = 'MIRAF2' THEN 2 ELSE 1 END,
       CASE WHEN code IN ('ETRC', 'CELAJES') THEN 2 ELSE 1 END,
       CASE WHEN code = 'NARANJO' THEN 'MON,TUE,WED' ELSE NULL END
FROM locations
WHERE code IN ('MIRAF2', 'ETRC', 'CELAJES', 'NARANJO')
ON CONFLICT (location_id) DO NOTHING;

-- Permisos (asignar a los roles de Contabilidad; ADMIN siempre puede). Cerrar sesión y volver a entrar.
INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.CALENDARIO.VER', 'Ver el calendario de visitas de conteo físico a kioscos', 'CONTABILIDAD', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.CALENDARIO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.CALENDARIO.GENERAR', 'Generar y mover visitas del calendario de conteo físico', 'CONTABILIDAD', 'GENERAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.CALENDARIO.GENERAR');

-- ───────── Historial de conteos y liquidación semanal (carga del Excel de contabilidad) ─────────

-- Períodos de inventario ya hechos (del–al) que se cargan del Excel «Liquidaciones por semana». El generador
-- los usa como «último conteo» de cada kiosco junto con los conteos físicos del sistema.
CREATE TABLE IF NOT EXISTS kiosk_count_history (
    id                  BIGSERIAL PRIMARY KEY,
    location_id         BIGINT NOT NULL REFERENCES locations (id),
    supervisor_user_id  BIGINT REFERENCES users (id),
    period_from         DATE,
    period_to           DATE   NOT NULL,                 -- fecha del conteo (el «al» del período)
    source              VARCHAR(12) NOT NULL DEFAULT 'EXCEL',
    imported_by         BIGINT,
    imported_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kiosk_count_history UNIQUE (location_id, period_to)
);

-- Liquidación: la revisión de contabilidad de cada conteo (si salió bien el conteo, depósitos, cambios, facturas
-- verdes), unas 4 semanas después de la visita. Se organiza por semana, un kiosco por día.
CREATE TABLE IF NOT EXISTS kiosk_liquidation (
    id                  BIGSERIAL PRIMARY KEY,
    location_id         BIGINT NOT NULL REFERENCES locations (id),
    supervisor_user_id  BIGINT NOT NULL REFERENCES users (id),
    period_from         DATE,
    period_to           DATE   NOT NULL,                 -- período del inventario que se liquida
    accountant          VARCHAR(80),                     -- quién de contabilidad lo revisa
    corrections_due     DATE,                            -- entrega de correcciones
    liquidation_date    DATE   NOT NULL,                 -- primer día de liquidación
    liquidation_days    INT    NOT NULL DEFAULT 1,       -- Entrecueros y Celajes: 2 días
    areas_to_fix        INT,                             -- «facturas verdes»: áreas por corregir (NULL = sin revisar)
    green_note          VARCHAR(300),                    -- texto original de facturas verdes
    status              VARCHAR(12) NOT NULL DEFAULT 'PENDING',   -- PENDING | LIQUIDATED
    note                VARCHAR(500),
    source              VARCHAR(12) NOT NULL DEFAULT 'GENERATED', -- IMPORT | GENERATED | MANUAL
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kiosk_liquidation UNIQUE (location_id, period_to),
    CONSTRAINT chk_kiosk_liquidation_status CHECK (status IN ('PENDING', 'LIQUIDATED')),
    CONSTRAINT chk_kiosk_liquidation_days CHECK (liquidation_days BETWEEN 1 AND 5)
);
CREATE INDEX IF NOT EXISTS idx_kiosk_liquidation_date ON kiosk_liquidation (liquidation_date);

-- Quién de contabilidad revisa cada liquidación: usuarios con el rol CONTABILIDAD (puede ser más de uno, como
-- «Fátima y Roberto» en el Excel). El texto original del Excel queda en kiosk_liquidation.accountant.
CREATE TABLE IF NOT EXISTS kiosk_liquidation_accountant (
    liquidation_id  BIGINT NOT NULL REFERENCES kiosk_liquidation (id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    PRIMARY KEY (liquidation_id, user_id)
);

-- Revisores adicionales: usuarios que no tienen el rol CONTABILIDAD pero también revisan liquidaciones
-- (Roberto Lique). Aparecen junto a contabilidad al asignar.
CREATE TABLE IF NOT EXISTS kiosk_liquidation_extra_reviewer (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE
);
INSERT INTO kiosk_liquidation_extra_reviewer (user_id)
SELECT id FROM users WHERE username = 'rob.lique'
ON CONFLICT (user_id) DO NOTHING;

-- ───────── Borrador por aprobar ─────────
-- Antes de quedar listo, cada semana se propone como BORRADOR (visitas en estado DRAFT) que se puede editar y que
-- contabilidad aprueba; al aprobarlo las visitas pasan a PLANNED y la semana a APPROVED. Las semanas que ya estaban
-- generadas quedan APROBADAS.
ALTER TABLE kiosk_count_visit DROP CONSTRAINT IF EXISTS chk_kiosk_count_visit_status;
ALTER TABLE kiosk_count_visit ADD CONSTRAINT chk_kiosk_count_visit_status
    CHECK (status IN ('DRAFT', 'PLANNED', 'DONE', 'CANCELLED'));

ALTER TABLE kiosk_count_week ADD COLUMN IF NOT EXISTS status VARCHAR(10) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE kiosk_count_week DROP CONSTRAINT IF EXISTS chk_kiosk_count_week_status;
ALTER TABLE kiosk_count_week ADD CONSTRAINT chk_kiosk_count_week_status CHECK (status IN ('DRAFT', 'APPROVED'));
