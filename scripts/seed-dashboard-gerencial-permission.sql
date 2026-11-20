-- Permiso del Tablero gerencial (Dashboards > Gerencial). Idempotente. No asigna roles.
-- Los roles ADMIN (y ADMIN_FULL_ACCESS / SUPER_ADMIN) ya lo ven sin este permiso.
-- Para que la gerente u otro rol lo vea: asignar DASHBOARDS.GERENCIAL.VER a ese rol en Seguridad > Roles,
-- y que la persona cierre sesión y vuelva a entrar.

INSERT INTO permission (code, description, module, action)
SELECT 'DASHBOARDS.GERENCIAL.VER', 'Ver el tablero gerencial (producción, ventas, rentabilidad y finanzas)', 'DASHBOARDS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DASHBOARDS.GERENCIAL.VER');
