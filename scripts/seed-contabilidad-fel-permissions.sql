-- Permisos módulo Contabilidad / Facturas FEL (sidebar + rutas POS/contabilidad)
-- Ejecutar una vez y luego asignar al rol/usuario. Cerrar sesión y volver a entrar.

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.FACTURAS.VER', 'Ver facturas FEL en contabilidad', 'CONTABILIDAD', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.FACTURAS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.FACTURAS.CREAR', 'Crear facturas FEL manuales', 'CONTABILIDAD', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.FACTURAS.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.FACTURAS.CERTIFICAR', 'Certificar / reintentar facturas FEL', 'CONTABILIDAD', 'CERTIFICAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.FACTURAS.CERTIFICAR');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.FACTURAS.ANULAR', 'Anular facturas FEL certificadas', 'CONTABILIDAD', 'ANULAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.FACTURAS.ANULAR');
