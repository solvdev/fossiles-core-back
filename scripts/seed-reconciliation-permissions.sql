-- Permisos del módulo de conciliación de ventas en línea. Idempotente. No asigna roles.
-- Por decisión del negocio (C16) proponer, autorizar, cerrar e importar fondos son solo de Administración y
-- Contabilidad: asigne CONCILIAR, IMPORTAR, AJUSTAR, AUTORIZAR, CERRAR y COMISIONES solo a esos roles.
-- Después de asignar, cerrar sesión y volver a entrar.

INSERT INTO permission (code, description, module, action)
SELECT v.code, v.description, 'VENTAS', v.action
FROM (VALUES
    ('VENTAS.CONCILIACION.VER',        'Ver la conciliación de ventas en línea',                          'VER'),
    ('VENTAS.CONCILIACION.CONCILIAR',  'Conciliar casos y aplicar fondos recibidos',                      'CONCILIAR'),
    ('VENTAS.CONCILIACION.IMPORTAR',   'Importar o capturar fondos recibidos (liquidaciones, extractos)', 'IMPORTAR'),
    ('VENTAS.CONCILIACION.AJUSTAR',    'Proponer un ajuste sobre una diferencia',                         'AJUSTAR'),
    ('VENTAS.CONCILIACION.AUTORIZAR',  'Autorizar o rechazar un ajuste y anular lotes',                   'AUTORIZAR'),
    ('VENTAS.CONCILIACION.CERRAR',     'Cerrar lotes y registrar la descarga',                            'CERRAR'),
    ('VENTAS.CONCILIACION.COMISIONES', 'Ver y cerrar las comisiones mensuales del vendedor',              'COMISIONES')
) AS v(code, description, action)
WHERE NOT EXISTS (SELECT 1 FROM permission p WHERE p.code = v.code);
