-- Rol LOGISTICA: inventario de kioskos (consulta cruzada + movimientos).
-- Ejecutar una vez en PostgreSQL. Cerrar sesión y volver a entrar para refrescar permisos.

INSERT INTO role (name, description)
SELECT 'LOGISTICA',
       'Logística: inventario de kioskos, traslados y consulta de existencias por producto'
WHERE NOT EXISTS (
    SELECT 1 FROM role
    WHERE UPPER(TRANSLATE(name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%LOGIST%'
);

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.INVENTARIO_KIOSKO.VER', 'Ver inventario del kiosko (kardex, traslados, conteos)', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.INVENTARIO_KIOSKO.VER');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE UPPER(TRANSLATE(r.name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%LOGIST%'
  AND p.code = 'KIOSCOS.INVENTARIO_KIOSKO.VER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
