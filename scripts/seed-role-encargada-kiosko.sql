-- Rol piloto: encargada de kiosko (POS + recepcion simple de envios)
-- Ajustar IDs si su BD ya tiene el rol con otro nombre.

INSERT INTO role (name, description)
SELECT 'ENCARGADA_KIOSKO', 'Encargada de kiosko: POS, reportes propios y recepcion de envios'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE UPPER(name) = 'ENCARGADA_KIOSKO');

-- Permisos minimos (crear permisos si no existen)
INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.VENTAS_KIOSKO.VER', 'Ver ventas POS del kiosko asignado', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.VENTAS_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.VENTAS_KIOSKO.CREAR', 'Registrar ventas POS del kiosko asignado', 'KIOSCOS', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.VENTAS_KIOSKO.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.CONFIRMACION_RECEPCION.VER', 'Ver envios pendientes de recepcion en su kiosko', 'DISTRIBUCION', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.CONFIRMACION_RECEPCION.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR', 'Confirmar recepcion de envios en su kiosko', 'DISTRIBUCION', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR');

-- Vincular permisos al rol
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE r.name = 'ENCARGADA_KIOSKO'
  AND p.code IN (
      'KIOSCOS.VENTAS_KIOSKO.VER',
      'KIOSCOS.VENTAS_KIOSKO.CREAR',
      'DISTRIBUCION.CONFIRMACION_RECEPCION.VER',
      'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR'
  )
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Encargada no autoriza cambios (solo supervisora/admin).
DELETE FROM role_permission rp
USING role r, permission p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.name = 'ENCARGADA_KIOSKO'
  AND p.code IN (
      'KIOSCOS.CAMBIOS.AUTORIZAR.VER',
      'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR'
  );
