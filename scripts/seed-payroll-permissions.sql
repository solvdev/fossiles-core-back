-- Permisos del módulo Planilla para Contabilidad y los roles de administración (el menú requiere el permiso
-- explícito aunque el backend acepte los roles ADMIN).
-- Ejecutar después de migration-payroll.sql. Cerrar sesión y volver a entrar.

INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CORRIDAS.VER', 'Ver planillas, salarios y descuentos', 'PLANILLA', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CORRIDAS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CORRIDAS.CREAR', 'Preparar y recalcular planillas en borrador', 'PLANILLA', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CORRIDAS.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CORRIDAS.APROBAR', 'Aprobar, numerar cheques, registrar el pago y anular planillas', 'PLANILLA', 'APROBAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CORRIDAS.APROBAR');

INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CARGOS.VER', 'Ver cargos por compras de colaborador', 'PLANILLA', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CARGOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'PLANILLA.CARGOS.EDITAR', 'Decidir descuento, transferencia o efectivo de cargos de colaborador', 'PLANILLA', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'PLANILLA.CARGOS.EDITAR');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE (upper(r.name) LIKE '%CONTAB%' OR upper(r.name) LIKE '%ADMIN%')
  AND p.code IN (
      'PLANILLA.CORRIDAS.VER',
      'PLANILLA.CORRIDAS.CREAR',
      'PLANILLA.CORRIDAS.APROBAR',
      'PLANILLA.CARGOS.VER',
      'PLANILLA.CARGOS.EDITAR'
  )
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
