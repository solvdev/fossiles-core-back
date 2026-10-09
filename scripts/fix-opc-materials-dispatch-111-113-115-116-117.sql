-- =====================================================================
-- Materiales cinchos: OPC-115/116/117 reabrir · OPC-111/113 marcar entregadas
-- (v2 — si la verificación sale todo "NO" / 0 pending, corre de nuevo este script)
-- =====================================================================
-- Causa típica de "todo NO": no hay tareas, o los ítems siguen materials_delivered=true.
-- Empaque/Entregar en la app se deshabilita cuando materials_delivered = true.
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — diagnóstico fuerte
-- =====================================================================
SELECT
    po.code,
    po.status AS op_status,
    (SELECT COUNT(*) FROM task t WHERE t.production_order_id = po.id) AS n_tasks,
    (SELECT COUNT(*) FROM task_item ti
     JOIN task t ON t.id = ti.task_id
     WHERE t.production_order_id = po.id) AS n_items,
    (SELECT COUNT(*) FROM task t
     WHERE t.production_order_id = po.id
       AND COALESCE(t.materials_delivered, false) IS NOT TRUE) AS tasks_flag_pending,
    (SELECT COUNT(*) FROM task_item ti
     JOIN task t ON t.id = ti.task_id
     WHERE t.production_order_id = po.id
       AND COALESCE(ti.materials_delivered, false) IS NOT TRUE) AS items_flag_pending,
    (SELECT COUNT(*) FROM task_item ti
     JOIN task t ON t.id = ti.task_id
     JOIN product p ON p.id = ti.product_id
     WHERE t.production_order_id = po.id
       AND p.requires_materials IS FALSE) AS items_producto_sin_materiales
FROM production_order po
WHERE UPPER(TRIM(po.code)) IN ('OPC-111', 'OPC-113', 'OPC-115', 'OPC-116', 'OPC-117')
ORDER BY po.code;

-- Detalle por tarea / ítem
SELECT
    po.code,
    t.id AS task_id,
    t.code AS task_code,
    t.status AS task_status,
    t.materials_delivered AS task_mat,
    ti.id AS item_id,
    ti.product_id,
    p.code AS product_code,
    p.requires_materials,
    ti.materials_delivered AS item_mat
FROM production_order po
LEFT JOIN task t ON t.production_order_id = po.id
LEFT JOIN task_item ti ON ti.task_id = t.id
LEFT JOIN product p ON p.id = ti.product_id
WHERE UPPER(TRIM(po.code)) IN ('OPC-111', 'OPC-113', 'OPC-115', 'OPC-116', 'OPC-117')
ORDER BY po.code, t.id, ti.id;

-- =====================================================================
-- B) UPDATES
-- =====================================================================
BEGIN;

-- B1) 115-117: OP abierta
UPDATE production_order po
SET status = CASE
        WHEN UPPER(TRIM(po.status)) IN ('COMPLETED', 'CANCELLED') THEN 'IN_PROGRESS'
        ELSE po.status
    END
WHERE UPPER(TRIM(po.code)) IN ('OPC-115', 'OPC-116', 'OPC-117');

-- B1) tareas: pendientes de materiales + gates listos (cuero/troquel) + no COMPLETED
UPDATE task t
SET materials_delivered = false,
    materials_delivered_at = NULL,
    leather_delivered = true,
    leather_delivered_at = COALESCE(t.leather_delivered_at, NOW()),
    die_cut_ready = true,
    die_cut_date = COALESCE(t.die_cut_date, CURRENT_DATE),
    status = CASE
        WHEN UPPER(TRIM(COALESCE(t.status, ''))) IN ('COMPLETED', 'CANCELLED') THEN 'PENDING'
        ELSE t.status
    END,
    completed_at = CASE
        WHEN UPPER(TRIM(COALESCE(t.status, ''))) = 'COMPLETED' THEN NULL
        ELSE t.completed_at
    END
WHERE t.production_order_id IN (
    SELECT id FROM production_order
    WHERE UPPER(TRIM(code)) IN ('OPC-115', 'OPC-116', 'OPC-117')
);

-- B1) ítems: pendientes (aunque el producto diga requires_materials=false, se fuerza entrega)
UPDATE task_item ti
SET materials_delivered = false,
    materials_delivered_at = NULL,
    leather_delivered = true,
    leather_delivered_at = COALESCE(ti.leather_delivered_at, NOW())
WHERE ti.task_id IN (
    SELECT t.id
    FROM task t
    JOIN production_order po ON po.id = t.production_order_id
    WHERE UPPER(TRIM(po.code)) IN ('OPC-115', 'OPC-116', 'OPC-117')
);

-- B2) 111, 113: ya despachadas → marcar entregado
UPDATE task_item ti
SET materials_delivered = true,
    materials_delivered_at = COALESCE(ti.materials_delivered_at, NOW())
WHERE ti.task_id IN (
    SELECT t.id
    FROM task t
    JOIN production_order po ON po.id = t.production_order_id
    WHERE UPPER(TRIM(po.code)) IN ('OPC-111', 'OPC-113')
);

UPDATE task t
SET materials_delivered = true,
    materials_delivered_at = COALESCE(t.materials_delivered_at, NOW())
WHERE t.production_order_id IN (
    SELECT id FROM production_order
    WHERE UPPER(TRIM(code)) IN ('OPC-111', 'OPC-113')
);

COMMIT;

-- Filas tocadas (debe ser > 0 en 115-117 si hay tareas)
SELECT 'tasks_115_117' AS que, COUNT(*) AS n
FROM task t
JOIN production_order po ON po.id = t.production_order_id
WHERE UPPER(TRIM(po.code)) IN ('OPC-115', 'OPC-116', 'OPC-117')
  AND COALESCE(t.materials_delivered, false) IS NOT TRUE
UNION ALL
SELECT 'items_115_117', COUNT(*)
FROM task_item ti
JOIN task t ON t.id = ti.task_id
JOIN production_order po ON po.id = t.production_order_id
WHERE UPPER(TRIM(po.code)) IN ('OPC-115', 'OPC-116', 'OPC-117')
  AND COALESCE(ti.materials_delivered, false) IS NOT TRUE;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT
    po.code,
    po.status AS op_status,
    (SELECT COUNT(*) FROM task t WHERE t.production_order_id = po.id) AS n_tasks,
    CASE
        WHEN (SELECT COUNT(*) FROM task t WHERE t.production_order_id = po.id) = 0
            THEN 'NO (sin tareas — generar materiales cincho en la app)'
        WHEN UPPER(TRIM(po.status)) IN ('CANCELLED', 'COMPLETED') THEN 'NO (OP cerrada)'
        WHEN EXISTS (
            SELECT 1
            FROM task t
            JOIN task_item ti ON ti.task_id = t.id
            WHERE t.production_order_id = po.id
              AND UPPER(TRIM(COALESCE(t.status, ''))) <> 'CANCELLED'
              AND COALESCE(ti.materials_delivered, false) IS NOT TRUE
        ) OR EXISTS (
            SELECT 1 FROM task t
            WHERE t.production_order_id = po.id
              AND UPPER(TRIM(COALESCE(t.status, ''))) <> 'CANCELLED'
              AND NOT EXISTS (SELECT 1 FROM task_item ti WHERE ti.task_id = t.id)
              AND COALESCE(t.materials_delivered, false) IS NOT TRUE
        ) THEN 'SÍ (pendiente materiales)'
        ELSE 'NO (materiales ok)'
    END AS aparece_en_despacho_materiales,
    (SELECT COUNT(*) FROM task_item ti
     JOIN task t ON t.id = ti.task_id
     WHERE t.production_order_id = po.id
       AND COALESCE(ti.materials_delivered, false) IS NOT TRUE) AS items_mat_pending
FROM production_order po
WHERE UPPER(TRIM(po.code)) IN ('OPC-111', 'OPC-113', 'OPC-115', 'OPC-116', 'OPC-117')
ORDER BY po.code;

-- Esperado:
--   OPC-115, 116, 117 → SÍ (o "sin tareas" → hay que generar tareas de materiales cincho)
--   OPC-111, 113     → NO (materiales ok)
--
-- Si sale "sin tareas": en la app abre la OPC y genera tareas de materiales cincho,
-- o avísame y armamos el INSERT de tareas.
