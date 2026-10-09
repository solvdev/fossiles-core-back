-- Permisos del módulo KIOSCOS para metas mensuales y comisión de kioskos.
-- Ejecutar una vez en PostgreSQL. ENCARGADA_KIOSKO: solo lectura de su propio kiosko.
-- SUPERVISORA_KIOSKO: lectura + configuración de metas y de asignación supervisora↔kiosko.
-- ADMIN no necesita permisos explícitos (se autoriza por nombre de rol en el backend).

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.METAS_KIOSKO.VER', 'Ver meta mensual, % logrado y comisión de un kiosko', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.METAS_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.METAS_KIOSKO.EDITAR', 'Configurar la meta de ventas mensual de un kiosko', 'KIOSCOS', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.METAS_KIOSKO.EDITAR');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.SUPERVISION_KIOSKO.VER', 'Ver panel agregado de comisión de una supervisora', 'KIOSCOS', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.SUPERVISION_KIOSKO.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'KIOSCOS.SUPERVISION_KIOSKO.EDITAR', 'Configurar qué kioskos supervisa cada supervisora', 'KIOSCOS', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'KIOSCOS.SUPERVISION_KIOSKO.EDITAR');

-- ENCARGADA_KIOSKO: solo ver su propia meta/comisión
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE r.name = 'ENCARGADA_KIOSKO'
  AND p.code = 'KIOSCOS.METAS_KIOSKO.VER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- SUPERVISORA_KIOSKO: ver y configurar metas y asignación de kioskos
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE r.name = 'SUPERVISORA_KIOSKO'
  AND p.code IN (
      'KIOSCOS.METAS_KIOSKO.VER',
      'KIOSCOS.METAS_KIOSKO.EDITAR',
      'KIOSCOS.SUPERVISION_KIOSKO.VER',
      'KIOSCOS.SUPERVISION_KIOSKO.EDITAR'
  )
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
