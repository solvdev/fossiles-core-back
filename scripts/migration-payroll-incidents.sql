-- Novedades de personal: horas extra, feriados trabajados, ausencias y vacaciones, que supervisoras, jefe de
-- producción y Contabilidad registran según la sección. También el kiosco de cada colaborador de kiosco.
-- Ejecutar ANTES de desplegar el back (ddl-auto=validate). Después, cerrar sesión y volver a entrar.

BEGIN;

-- Kiosco (locations.id) al que pertenece un colaborador de kiosco: define qué supervisora lo ve.
ALTER TABLE employee ADD COLUMN IF NOT EXISTS kiosk_location_id BIGINT REFERENCES locations(id);
CREATE INDEX IF NOT EXISTS idx_employee_kiosk_location ON employee(kiosk_location_id);

CREATE TABLE IF NOT EXISTS payroll_incident (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employee(id),
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('HORA_EXTRA', 'FERIADO_TRABAJADO', 'AUSENCIA', 'VACACIONES')),
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    hours NUMERIC(6,2),
    -- Pago por hora distinto del calculado con el recargo de los parámetros (solo horas extra y feriados).
    hourly_rate_override NUMERIC(10,2),
    holiday_name VARCHAR(100),
    note VARCHAR(300),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVA' CHECK (status IN ('ACTIVA', 'ANULADA')),
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    CHECK (date_to >= date_from)
);
CREATE INDEX IF NOT EXISTS idx_payroll_incident_employee ON payroll_incident(employee_id, date_from);
CREATE INDEX IF NOT EXISTS idx_payroll_incident_dates ON payroll_incident(date_from, date_to) WHERE status = 'ACTIVA';

-- Lo que la planilla pagó o descontó por novedades en cada línea.
ALTER TABLE payroll_run_line ADD COLUMN IF NOT EXISTS extra_pay NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE payroll_run_line ADD COLUMN IF NOT EXISTS absence_days NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE payroll_run_line ADD COLUMN IF NOT EXISTS vacation_days NUMERIC(5,2) NOT NULL DEFAULT 0;

-- Parámetros de recargo, con vigencia como los demás. Contabilidad los ajusta en Planilla > Parámetros.
INSERT INTO payroll_parameter (code, param_value, effective_from, description) VALUES
    ('HORA_EXTRA_FACTOR', 1.5, DATE '2026-01-01', 'Cada hora extra se paga a este múltiplo del valor de la hora ordinaria'),
    ('FERIADO_TRABAJADO_FACTOR', 2, DATE '2026-01-01', 'Cada hora trabajada en feriado se paga a este múltiplo del valor de la hora, además del sueldo mensual'),
    ('JORNADA_HORAS', 8, DATE '2026-01-01', 'Horas de la jornada diaria; el valor de la hora es el sueldo mensual entre 30 y entre estas horas')
ON CONFLICT (code, effective_from) DO NOTHING;

-- Permisos por sección: quién registra novedades de quién.
INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.NOVEDADES.PLANTA', 'Registrar novedades del personal de planta (jefe de producción)', 'PLANILLA', 'NOVEDADES'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.NOVEDADES.PLANTA');
INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.NOVEDADES.KIOSCOS', 'Registrar novedades del personal de los kioscos que supervisa', 'PLANILLA', 'NOVEDADES'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.NOVEDADES.KIOSCOS');
INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.NOVEDADES.ADMINISTRACION', 'Registrar novedades del personal de administración (sin kioscos)', 'PLANILLA', 'NOVEDADES'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.NOVEDADES.ADMINISTRACION');

-- Punto de partida por nombre de rol; ajústelo en Roles si algún nombre no coincide.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE p.code = 'PLANILLA.NOVEDADES.KIOSCOS' AND upper(r.name) LIKE '%SUPERVIS%' AND upper(r.name) LIKE '%KIOS%'
  AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE p.code = 'PLANILLA.NOVEDADES.PLANTA' AND upper(r.name) LIKE '%PRODUC%' AND (upper(r.name) LIKE '%JEFE%' OR upper(r.name) LIKE '%SUPERVIS%')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE p.code = 'PLANILLA.NOVEDADES.ADMINISTRACION' AND upper(r.name) LIKE '%CONTAB%'
  AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

COMMIT;
