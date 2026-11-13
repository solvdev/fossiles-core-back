-- Planilla: ficha laboral, parámetros con vigencia, corridas por fecha de pago y cargos a empleados.
-- Debe ejecutarse antes de arrancar el backend que la usa (ddl-auto=validate).
BEGIN;
SET LOCAL lock_timeout = '5s';

-- Ficha laboral
ALTER TABLE employee ADD COLUMN IF NOT EXISTS employment_type VARCHAR(20) NOT NULL DEFAULT 'PLANILLA';
ALTER TABLE employee ADD COLUMN IF NOT EXISTS pay_group VARCHAR(20);
ALTER TABLE employee ADD COLUMN IF NOT EXISTS nit VARCHAR(20);
ALTER TABLE employee ADD COLUMN IF NOT EXISTS igss_affiliation VARCHAR(30);
ALTER TABLE employee ADD COLUMN IF NOT EXISTS termination_date DATE;

-- Planta (Producción) cobra el 5 y el 20; Administración y Kioscos, el 15 y a fin de mes.
UPDATE employee e SET pay_group = CASE
        WHEN upper(coalesce(d.name, '')) LIKE '%PRODUCCI%' OR upper(coalesce(d.code, '')) LIKE '%PRODUC%' THEN 'PLANTA'
        ELSE 'ADMINISTRACION' END
FROM department d WHERE d.id = e.department_id AND e.pay_group IS NULL;
UPDATE employee SET pay_group = 'ADMINISTRACION' WHERE pay_group IS NULL;
ALTER TABLE employee ALTER COLUMN pay_group SET DEFAULT 'ADMINISTRACION';
ALTER TABLE employee ALTER COLUMN pay_group SET NOT NULL;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employee_employment_type') THEN
        ALTER TABLE employee ADD CONSTRAINT chk_employee_employment_type CHECK (employment_type IN ('PLANILLA', 'SERVICIOS'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employee_pay_group') THEN
        ALTER TABLE employee ADD CONSTRAINT chk_employee_pay_group CHECK (pay_group IN ('PLANTA', 'ADMINISTRACION'));
    END IF;
END $$;

-- Forma de pago en su grafía estándar; como en PayrollRules.canonicalPaymentMethod, se comparan solo las letras.
UPDATE employee SET payment_method = CASE regexp_replace(upper(payment_method), '[^A-Z]', '', 'g')
        WHEN 'TRANSFERENCIABANCARIA' THEN 'TRANSFERENCIA BANCARIA'
        WHEN 'TRANSFERENCIA' THEN 'TRANSFERENCIA BANCARIA'
        WHEN 'CHEQUE' THEN 'CHEQUE'
        ELSE 'EFECTIVO' END
WHERE regexp_replace(upper(payment_method), '[^A-Z]', '', 'g') IN ('TRANSFERENCIABANCARIA', 'TRANSFERENCIA', 'CHEQUE', 'EFECTIVO')
  AND payment_method NOT IN ('TRANSFERENCIA BANCARIA', 'CHEQUE', 'EFECTIVO');

-- Historial de salario y bonificación con fecha de vigencia.
CREATE TABLE IF NOT EXISTS employee_salary_history (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    effective_from DATE NOT NULL,
    salary NUMERIC(10,2),
    bonification NUMERIC(10,2),
    recorded_at TIMESTAMP NOT NULL DEFAULT now(),
    recorded_by BIGINT
);
CREATE INDEX IF NOT EXISTS idx_employee_salary_history_employee ON employee_salary_history(employee_id, effective_from DESC);
INSERT INTO employee_salary_history (employee_id, effective_from, salary, bonification)
SELECT e.id, coalesce(e.hire_date, CURRENT_DATE), e.salary, e.bonification FROM employee e
WHERE e.salary IS NOT NULL AND NOT EXISTS (SELECT 1 FROM employee_salary_history h WHERE h.employee_id = e.id);

-- Parámetros legales con vigencia
CREATE TABLE IF NOT EXISTS payroll_parameter (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    param_value NUMERIC(14,4) NOT NULL,
    effective_from DATE NOT NULL,
    description VARCHAR(200),
    UNIQUE (code, effective_from)
);
INSERT INTO payroll_parameter (code, param_value, effective_from, description) VALUES
    ('IGSS_LABORAL_PCT', 4.83, DATE '2026-01-01', 'IGSS cuota laboral sobre el salario'),
    ('IGSS_PATRONAL_PCT', 10.67, DATE '2026-01-01', 'IGSS cuota patronal'),
    ('IRTRA_PCT', 1.00, DATE '2026-01-01', 'IRTRA patronal'),
    ('INTECAP_PCT', 1.00, DATE '2026-01-01', 'INTECAP patronal'),
    ('BONIFICACION_INCENTIVO_MENSUAL', 250.00, DATE '2026-01-01', 'Bonificación incentivo mensual (Decreto 37-2001) si la ficha no indica otra'),
    ('ISR_DEDUCCION_PERSONAL', 48000.00, DATE '2026-01-01', 'Deducción anual sin comprobación'),
    ('ISR_TRAMO_LIMITE', 300000.00, DATE '2026-01-01', 'Límite anual del primer tramo'),
    ('ISR_TASA_TRAMO1_PCT', 5.00, DATE '2026-01-01', 'Tasa del primer tramo'),
    ('ISR_TASA_TRAMO2_PCT', 7.00, DATE '2026-01-01', 'Tasa sobre el excedente del primer tramo'),
    ('ISR_CUOTA_FIJA_TRAMO2', 15000.00, DATE '2026-01-01', 'Impuesto fijo del primer tramo completo')
ON CONFLICT (code, effective_from) DO NOTHING;

-- Corridas de planilla por grupo y fecha de pago
CREATE TABLE IF NOT EXISTS payroll_run (
    id BIGSERIAL PRIMARY KEY,
    pay_group VARCHAR(20) NOT NULL CHECK (pay_group IN ('PLANTA', 'ADMINISTRACION')),
    scheduled_date DATE NOT NULL,
    pay_date DATE NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'BORRADOR' CHECK (status IN ('BORRADOR', 'APROBADA', 'PAGADA', 'ANULADA')),
    bank_name VARCHAR(100),
    notes VARCHAR(500),
    total_gross NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_deductions NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_net NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by BIGINT,
    calculated_at TIMESTAMP,
    calculation_hash VARCHAR(64),
    approved_at TIMESTAMP,
    approved_by BIGINT,
    paid_at TIMESTAMP,
    paid_by BIGINT,
    cancelled_at TIMESTAMP,
    cancelled_by BIGINT,
    cancellation_reason VARCHAR(500)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payroll_run_active_date ON payroll_run(pay_group, scheduled_date) WHERE status <> 'ANULADA';

CREATE TABLE IF NOT EXISTS payroll_run_line (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES payroll_run(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employee(id),
    employee_name VARCHAR(200) NOT NULL,
    position VARCHAR(100),
    department_id BIGINT,
    cost_center_id BIGINT,
    employment_type VARCHAR(20) NOT NULL,
    payment_method VARCHAR(50),
    bank_account VARCHAR(50),
    monthly_salary NUMERIC(10,2) NOT NULL DEFAULT 0,
    days_paid NUMERIC(5,2) NOT NULL DEFAULT 0,
    base_salary NUMERIC(12,2) NOT NULL DEFAULT 0,
    bonification NUMERIC(12,2) NOT NULL DEFAULT 0,
    igss_employee NUMERIC(12,2) NOT NULL DEFAULT 0,
    isr NUMERIC(12,2) NOT NULL DEFAULT 0,
    opi_discount NUMERIC(12,2) NOT NULL DEFAULT 0,
    other_deductions NUMERIC(12,2) NOT NULL DEFAULT 0,
    net_pay NUMERIC(12,2) NOT NULL DEFAULT 0,
    igss_employer NUMERIC(12,2) NOT NULL DEFAULT 0,
    irtra NUMERIC(12,2) NOT NULL DEFAULT 0,
    intecap NUMERIC(12,2) NOT NULL DEFAULT 0,
    cheque_number VARCHAR(30),
    UNIQUE (run_id, employee_id)
);

-- Cargos a empleados (compras de colaborador al 50 %) y su descuento en planilla
CREATE TABLE IF NOT EXISTS employee_charge (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employee(id),
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT,
    description VARCHAR(300),
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    balance NUMERIC(12,2) NOT NULL CHECK (balance >= 0),
    settlement_method VARCHAR(20) CHECK (settlement_method IN ('DESCUENTO', 'TRANSFERENCIA', 'EFECTIVO')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE' CHECK (status IN ('PENDIENTE', 'PARCIAL', 'PAGADO', 'ANULADO')),
    charged_on DATE NOT NULL,
    decided_by BIGINT,
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by BIGINT,
    UNIQUE (source_type, source_id)
);
CREATE INDEX IF NOT EXISTS idx_employee_charge_employee ON employee_charge(employee_id, status);

CREATE TABLE IF NOT EXISTS employee_charge_application (
    id BIGSERIAL PRIMARY KEY,
    charge_id BIGINT NOT NULL REFERENCES employee_charge(id),
    run_line_id BIGINT NOT NULL REFERENCES payroll_run_line(id) ON DELETE CASCADE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    UNIQUE (charge_id, run_line_id)
);

COMMIT;
