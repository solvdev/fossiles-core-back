-- Rol: supervisora de kiosko (todos los locales, sin POS de cajera)
-- Ejecutar una vez en PostgreSQL. Luego asignar el rol al usuario en Usuarios → Roles.
-- Cerrar sesión y volver a entrar para refrescar permisos en el frontend.

INSERT INTO role (name, description)
SELECT 'SUPERVISORA_KIOSKO',
       'Supervisora de kiosko: inventario, distribución, devoluciones/cambios y reportes en todos los kioskos'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE UPPER(name) = 'SUPERVISORA_KIOSKO');

-- Permisos del módulo KIOSCOS
INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.INVENTARIO_KIOSKO.VER', 'Ver inventario del kiosko (kardex, traslados, conteos)', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.INVENTARIO_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.VENTAS_KIOSKO.VER', 'Ver ventas POS y panel gerencial de kioskos', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.VENTAS_KIOSKO.VER');

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

-- Inventarios (ajustes/transferencias a kiosko — requerido por KioskInventoryGuard)
INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.PRODUCTOS.VER', 'Ver inventario de productos por ubicación', 'INVENTARIOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.PRODUCTOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.KARDEX_PRODUCTOS.VER', 'Ver kardex de productos', 'INVENTARIOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.KARDEX_PRODUCTOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.AJUSTES_PRODUCTOS.VER', 'Ver ajustes de inventario de productos', 'INVENTARIOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.AJUSTES_PRODUCTOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.AJUSTES_PRODUCTOS.CREAR', 'Crear ajustes de inventario de productos', 'INVENTARIOS', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.AJUSTES_PRODUCTOS.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.AJUSTES_PRODUCTOS.EDITAR', 'Editar ajustes de inventario de productos', 'INVENTARIOS', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.AJUSTES_PRODUCTOS.EDITAR');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.TRANSFERENCIAS.VER', 'Ver transferencias de inventario', 'INVENTARIOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.TRANSFERENCIAS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'INVENTARIOS.TRANSFERENCIAS.CREAR', 'Crear transferencias de inventario', 'INVENTARIOS', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'INVENTARIOS.TRANSFERENCIAS.CREAR');

-- Distribución (enviar mercadería y confirmar recepción con cantidades parciales)
INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.DISTRIBUCIONES.VER', 'Ver distribuciones de productos', 'DISTRIBUCION', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.DISTRIBUCIONES.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.DISTRIBUCIONES.CREAR', 'Crear distribuciones de productos', 'DISTRIBUCION', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.DISTRIBUCIONES.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.DISTRIBUCIONES.EDITAR', 'Editar y enviar distribuciones de productos', 'DISTRIBUCION', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.DISTRIBUCIONES.EDITAR');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.ENVIOS_TRANSITO.VER', 'Ver envíos en tránsito', 'DISTRIBUCION', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.ENVIOS_TRANSITO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.CONFIRMACION_RECEPCION.VER', 'Ver confirmación de recepción de envíos', 'DISTRIBUCION', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.CONFIRMACION_RECEPCION.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR', 'Confirmar recepción de envíos (incluye parciales)', 'DISTRIBUCION', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR');

-- Reportes
INSERT INTO permission (code, description, module, action)
SELECT 'REPORTES.DESEMPENO_KIOSCOS.VER', 'Ver reporte de desempeño de kioscos', 'REPORTES', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'REPORTES.DESEMPENO_KIOSCOS.VER');

-- Vincular permisos al rol
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE r.name = 'SUPERVISORA_KIOSKO'
  AND p.code IN (
      'KIOSCOS.INVENTARIO_KIOSKO.VER',
      'KIOSCOS.VENTAS_KIOSKO.VER',
      'KIOSCOS.DEVOLUCIONES_REINTEGROS.VER',
      'KIOSCOS.DEVOLUCIONES_REINTEGROS.CREAR',
      'KIOSCOS.CAMBIOS.AUTORIZAR.VER',
      'KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR',
      'INVENTARIOS.PRODUCTOS.VER',
      'INVENTARIOS.KARDEX_PRODUCTOS.VER',
      'INVENTARIOS.AJUSTES_PRODUCTOS.VER',
      'INVENTARIOS.AJUSTES_PRODUCTOS.CREAR',
      'INVENTARIOS.AJUSTES_PRODUCTOS.EDITAR',
      'INVENTARIOS.TRANSFERENCIAS.VER',
      'INVENTARIOS.TRANSFERENCIAS.CREAR',
      'DISTRIBUCION.DISTRIBUCIONES.VER',
      'DISTRIBUCION.DISTRIBUCIONES.CREAR',
      'DISTRIBUCION.DISTRIBUCIONES.EDITAR',
      'DISTRIBUCION.ENVIOS_TRANSITO.VER',
      'DISTRIBUCION.CONFIRMACION_RECEPCION.VER',
      'DISTRIBUCION.CONFIRMACION_RECEPCION.CREAR',
      'REPORTES.DESEMPENO_KIOSCOS.VER'
  )
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
