-- Finanzas por kiosco: costos fijos, tasas, metas, ventas históricas (Excel) y lotes de importación.
-- Idempotente. Ejecutar a mano en PostgreSQL ANTES de desplegar el backend (ddl-auto=validate).
-- Ver docs/KIOSK-FINANCIALS-CONTRACT.md

-- ---------------------------------------------------------------------------
-- Sitios (kioscos reales + históricos que ya no existen en locations)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_site (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    location_id BIGINT REFERENCES locations(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE | CLOSED
    closed_on DATE,
    pos_go_live_override DATE,                            -- si se llena, manda sobre la 1a venta real detectada
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosk_site_location
    ON kiosk_site (location_id) WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosk_site_name
    ON kiosk_site (UPPER(name));

-- Alias = nombre de columna del Excel normalizado (ver contrato: mayúsculas, sin acentos, solo A-Z0-9 y espacio)
CREATE TABLE IF NOT EXISTS kiosk_site_alias (
    id BIGSERIAL PRIMARY KEY,
    alias_normalized VARCHAR(120) NOT NULL,
    site_id BIGINT NOT NULL REFERENCES kiosk_site(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_kiosk_site_alias ON kiosk_site_alias (alias_normalized);

-- ---------------------------------------------------------------------------
-- Categorías de costo fijo (extensible)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_cost_category (
    code VARCHAR(40) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO kiosk_cost_category (code, name, sort_order) VALUES
    ('ALQUILER', 'Alquiler', 1),
    ('LUZ', 'Luz', 2),
    ('TELEFONO_INTERNET_PROG', 'Teléfono, Internet y programación', 3),
    ('MANTENIMIENTO', 'Mantenimiento', 4),
    ('SALARIOS_MO_INDIRECTA', 'Salarios MO indirecta', 5),
    ('BONIFICACION', 'Bonificación', 6),
    ('INDEMNIZACION_VACACIONES', 'Indemnización y vacaciones', 7),
    ('BONO_14', 'Bono 14', 8),
    ('AGUINALDO', 'Aguinaldo', 9),
    ('SALARIOS_MO_DIRECTA', 'Salarios MO directa', 10)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Costos fijos por sitio/año/mes/categoría
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_fixed_cost (
    site_id BIGINT NOT NULL REFERENCES kiosk_site(id) ON DELETE CASCADE,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    category_code VARCHAR(40) NOT NULL REFERENCES kiosk_cost_category(code),
    amount NUMERIC(12, 2) NOT NULL,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (site_id, year, month, category_code)
);

-- ---------------------------------------------------------------------------
-- Configuración mensual por sitio: meta y tasas (decimales: 0.18 = 18 %)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_period_config (
    site_id BIGINT NOT NULL REFERENCES kiosk_site(id) ON DELETE CASCADE,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    sales_goal NUMERIC(12, 2),
    product_cost_pct NUMERIC(6, 4),
    sales_commission_pct NUMERIC(6, 4),
    card_commission_pct NUMERIC(6, 4),
    tax_pct NUMERIC(6, 4),
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',         -- EXCEL | COPIED | MANUAL
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (site_id, year, month)
);

-- ---------------------------------------------------------------------------
-- Lotes de importación (idempotencia + reversión)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_import_batch (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED',        -- APPLIED | REVERTED
    sales_rows INTEGER NOT NULL DEFAULT 0,
    config_rows INTEGER NOT NULL DEFAULT 0,
    cost_rows INTEGER NOT NULL DEFAULT 0,
    warnings_json TEXT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reverted_by BIGINT,
    reverted_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kiosk_import_batch_period ON kiosk_import_batch (year, month);

-- ---------------------------------------------------------------------------
-- Ventas diarias históricas (Excel). Celda vacía = sin fila; 0 = fila con 0.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kiosk_daily_sales_hist (
    site_id BIGINT NOT NULL REFERENCES kiosk_site(id) ON DELETE CASCADE,
    sale_date DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,                       -- IVA incluido, igual que kiosk_sale.total_amount
    import_batch_id BIGINT REFERENCES kiosk_import_batch(id),
    PRIMARY KEY (site_id, sale_date)
);
CREATE INDEX IF NOT EXISTS idx_kiosk_daily_sales_hist_date ON kiosk_daily_sales_hist (sale_date);
CREATE INDEX IF NOT EXISTS idx_kiosk_daily_sales_hist_batch ON kiosk_daily_sales_hist (import_batch_id);

-- Trazabilidad de qué lote escribió costos/config (para revertir)
ALTER TABLE kiosk_fixed_cost   ADD COLUMN IF NOT EXISTS import_batch_id BIGINT REFERENCES kiosk_import_batch(id);
ALTER TABLE kiosk_period_config ADD COLUMN IF NOT EXISTS import_batch_id BIGINT REFERENCES kiosk_import_batch(id);

-- ---------------------------------------------------------------------------
-- Backfill de sitios desde locations (kioscos reales).
-- Excluye kiosco demo (TST) y "PLAZA DEL PARQUE COBAN" (PDP_COBAN): ya no existe, queda como sitio histórico.
-- ---------------------------------------------------------------------------
INSERT INTO kiosk_site (name, location_id, status, sort_order)
SELECT TRIM(REGEXP_REPLACE(l.name, '^CUEROGLAM\s+', '', 'i')), l.id, 'ACTIVE', l.id
FROM locations l
WHERE UPPER(l.categoria) = 'KIOSKO'
  AND UPPER(COALESCE(l.code, '')) NOT IN ('TST', 'PDP_COBAN')
  AND NOT EXISTS (SELECT 1 FROM kiosk_site s WHERE s.location_id = l.id);

-- Sitios históricos (sin location): cerrados o inexistentes en el POS
INSERT INTO kiosk_site (name, location_id, status, closed_on, sort_order)
SELECT v.name, NULL, 'CLOSED', v.closed_on::date, v.sort_order
FROM (VALUES
    ('MAJADAS 11',      '2025-03-31', 1001),
    ('SANTA AMELIA',    '2025-08-31', 1002),
    ('CUERISIMOS',      '2025-07-31', 1003),
    ('EL QUICHE',       NULL,         1004),
    ('EL PARQUE COBAN', NULL,         1005)
) AS v(name, closed_on, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM kiosk_site s WHERE UPPER(s.name) = UPPER(v.name));

-- ---------------------------------------------------------------------------
-- Alias: nombre en columnas del Excel (normalizado) -> location.code
-- ---------------------------------------------------------------------------
INSERT INTO kiosk_site_alias (alias_normalized, site_id)
SELECT m.alias, s.id
FROM (VALUES
    ('MIRAFLORES',            'MIRAF2'),
    ('PERI',                  'PROOSEV'),
    ('ESKALA',                'ESKALA'),
    ('PORTALES',              'PORTALES'),
    ('METRONORTE',            'METRONRT'),
    ('NARANJO',               'NARANJO'),
    ('JUTIAPA',               'JUTIAPA'),
    ('CHIQUIMULA',            'CHIQUIMU'),
    ('ESCUINTLA',             'PRAESC'),
    ('CHIMALTENANGO',         'CHIMALTEN'),
    ('SANKRIS',               'SANKRIS'),
    ('PACIFIC',               'PACIFICCTR'),
    ('SANTA CLARA',           'SANTACLR'),
    ('EL FRUTAL',             'ELFRUTAL'),
    ('ATANASIO',              'ATANASIO'),
    ('PLAZA CEMACO',          'PCEMACO'),
    ('ZONA 4',                'ZONA4'),
    ('SAN LUCAS',             'SANLUCAS'),
    ('COBAN',                 'MAGDACOBAN'),
    ('REU',                   'RETALHU'),
    ('COATEPEQUE',            'COATEPEQ'),
    ('MAZATENANGO',           'MAZATEN'),
    ('INTERPLAZA XELA',       'INT_XELA'),
    ('UTZ ULEW',              'UTZULEW'),
    ('VISTARES',              'PRAVIST'),
    ('PRADERA XELA',          'PRAXELA'),
    ('TIKAL FUTURA',          'TIKAL'),
    ('ANDARIA',               'ANDARIA'),
    ('INTERPLAZA ESCUINTLA',  'INT_ESCUINT'),
    ('SANTALU',               'SANTALU'),
    ('SANTAL',                'SANTALU'),   -- "SANTAL�" con carácter corrupto en los Excel
    ('RUS MALL',              'RUSMALL'),
    ('JALAPA',                'JALAPA'),
    ('PRADERA CONCEPCION',    'PRACONC'),
    ('METROCENTRO',           'METROCTRO'),
    ('TELARES',               'PTELARES')
) AS m(alias, loc_code)
JOIN locations l ON UPPER(l.code) = m.loc_code
JOIN kiosk_site s ON s.location_id = l.id
ON CONFLICT (alias_normalized) DO NOTHING;

-- Alias de sitios históricos (mismo nombre normalizado)
INSERT INTO kiosk_site_alias (alias_normalized, site_id)
SELECT UPPER(s.name), s.id FROM kiosk_site s
WHERE s.location_id IS NULL
ON CONFLICT (alias_normalized) DO NOTHING;
