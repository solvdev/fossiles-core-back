-- Rol venta en línea: módulo Kioscos completo (inventario, Dónde está, POS, devoluciones/cambios).
-- Ejecutar una vez en PostgreSQL. Cerrar sesión y volver a entrar para refrescar permisos.

INSERT INTO role (name, description)
SELECT 'VENTA_EN_LINEA',
       'Venta en línea: ventas online y módulo Kioscos completo (inventario, Dónde está, POS y devoluciones)'
WHERE NOT EXISTS (
    SELECT 1 FROM role
    WHERE UPPER(TRANSLATE(name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%VENTA%LINEA%'
       OR UPPER(TRANSLATE(name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%SALES%ONLINE%'
);

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.INVENTARIO_KIOSKO.VER', 'Ver inventario del kiosko (kardex, traslados, conteos, Dónde está)', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.INVENTARIO_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.VENTAS_KIOSKO.VER', 'Ver ventas POS y panel gerencial de kioskos', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.VENTAS_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.VENTAS_KIOSKO.CREAR', 'Registrar ventas POS de kiosko', 'KIOSCOS', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.VENTAS_KIOSKO.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.DEVOLUCIONES_REINTEGROS.VER', 'Ver devoluciones, reintegros y cambios de kiosko', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.DEVOLUCIONES_REINTEGROS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.DEVOLUCIONES_REINTEGROS.CREAR', 'Registrar devoluciones, reintegros y cambios de kiosko', 'KIOSCOS', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.DEVOLUCIONES_REINTEGROS.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.CAMBIOS.AUTORIZAR.VER', 'Ver solicitudes de cambio pendientes de autorización', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.CAMBIOS.AUTORIZAR.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR', 'Autorizar o rechazar cambios sin diferencia de precio', 'KIOSCOS', 'APROBAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE (
        UPPER(TRANSLATE(r.name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%VENTA%LINEA%'
     OR UPPER(TRANSLATE(r.name, 'ÁÉÍÓÚáéíóú', 'AEIOUAEIOU')) LIKE '%SALES%ONLINE%'
  )
  AND p.code IN (
      'KIOSCOS.INVENTARIO_KIOSKO.VER',
      'KIOSCOS.VENTAS_KIOSKO.VER',
      'KIOSCOS.VENTAS_KIOSKO.CREAR',
      'KIOSCOS.DEVOLUCIONES_REINTEGROS.VER',
      'KIOSCOS.DEVOLUCIONES_REINTEGROS.CREAR',
      'KIOSCOS.CAMBIOS.AUTORIZAR.VER',
      'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR'
  )
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
