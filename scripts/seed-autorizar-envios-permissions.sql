-- Permisos Autorizar envíos internos ENVI
-- Ejecutar una vez y asignar roles. Cerrar sesión y volver a entrar.

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.AUTORIZAR_ENVIOS.VER', 'Ver solicitudes pendientes de envío interno ENVI', 'DISTRIBUCION', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.AUTORIZAR_ENVIOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'DISTRIBUCION.AUTORIZAR_ENVIOS.CREAR', 'Crear solicitudes de envío interno ENVI', 'DISTRIBUCION', 'CREAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'DISTRIBUCION.AUTORIZAR_ENVIOS.CREAR');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.ENVIOS.VER', 'Ver solicitudes de envío interno para autorizar', 'CONTABILIDAD', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.ENVIOS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.ENVIOS.APROBAR', 'Aprobar o denegar solicitudes de envío interno ENVI', 'CONTABILIDAD', 'APROBAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.ENVIOS.APROBAR');
