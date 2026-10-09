-- Permisos módulo Finanzas de kioscos (costos, metas, reportes P&L y comparativos, importación Excel).
-- Ejecutar una vez, asignar a roles (contabilidad / administración) y volver a iniciar sesión.

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.FINANZAS.VER', 'Ver reportes financieros de kioscos (P&L, comparativos, costos)', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.FINANZAS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.FINANZAS.EDITAR', 'Editar costos, tasas, metas y sitios de kioscos', 'KIOSCOS', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.FINANZAS.EDITAR');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.FINANZAS.IMPORTAR', 'Importar reportes de ventas/costos desde Excel', 'KIOSCOS', 'IMPORTAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.FINANZAS.IMPORTAR');
