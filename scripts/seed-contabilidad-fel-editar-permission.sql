-- Permiso para corregir UUID/serie/número/fecha FEL en ventas (pruebas → factura real SAT)
INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.FACTURAS.EDITAR', 'Corregir datos FEL certificados (UUID, serie, número, fecha)', 'CONTABILIDAD', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.FACTURAS.EDITAR');
