-- Cargos manuales a colaboradores (descuadres, faltantes de caja, daños y otros).
-- Ejecutar ANTES de desplegar el back: las columnas nuevas las valida Hibernate (ddl-auto=validate).
-- Después, cerrar sesión y volver a entrar para ver el permiso nuevo.

BEGIN;

ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS concept VARCHAR(30);
ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS reason VARCHAR(500);
ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS reference VARCHAR(100);
ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS employee_authorized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS authorization_note VARCHAR(200);
-- Tope que la planilla descuenta de este cargo en cada pago (cuota por quincena). Vacío: lo que alcance.
ALTER TABLE employee_charge ADD COLUMN IF NOT EXISTS installment_amount NUMERIC(12,2);
ALTER TABLE employee_charge DROP CONSTRAINT IF EXISTS employee_charge_installment_positive;
ALTER TABLE employee_charge ADD CONSTRAINT employee_charge_installment_positive
    CHECK (installment_amount IS NULL OR installment_amount > 0);

-- Permiso para registrar cargos manuales (Contabilidad y administración).
INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CARGOS.CREAR', 'Registrar cargos manuales a colaboradores (descuadres, faltantes, daños)', 'PLANILLA', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CARGOS.CREAR');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE (upper(r.name) LIKE '%CONTAB%' OR upper(r.name) LIKE '%ADMIN%')
  AND p.code = 'PLANILLA.CARGOS.CREAR'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

COMMIT;
