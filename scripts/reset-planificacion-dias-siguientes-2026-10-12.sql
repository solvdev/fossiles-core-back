-- =====================================================================
-- Limpiar la planificacion de los dias siguientes (arranque limpio del lunes)
-- =====================================================================
-- Objetivo: que desde el sabado 2026-10-10 en adelante no quede ninguna tarea
-- puesta en un dia ni en una mesa, para planificar el lunes 2026-10-12 desde
-- cero con el flujo del Organizador:
--     Organizar -> Por troquelar -> Planificar / Distribuir el dia.
--
-- El corte es el sabado 10 y no el lunes 12 para no dejar tareas sueltas en el
-- fin de semana (el reparto solo trabaja de lunes a viernes): el lunes 12 y
-- todo lo posterior queda libre para reorganizarse.
--
-- QUE HACE
--   Las tareas PENDIENTES con scheduled_date >= '2026-10-10' pasan a
--   desk = NULL y scheduled_date = NULL (vuelven a la bandeja "sin mesa").
--
-- QUE NO HACE
--   * NO borra tareas: cada una cuelga de su OP y de sus productos, y ahi
--     viven el cuero entregado y el corte marcado. Se conservan intactos
--     (task.die_cut_ready, task_item.die_cut_ready, leather_delivered...).
--   * NO toca el dia de hoy ni anteriores.
--   * NO toca tareas IN_PROGRESS, AWAITING_WAREHOUSE, COMPLETED ni CANCELLED:
--     lo que ya se empezo o termino no se mueve.
--
-- REVERSIBLE: antes de modificar guarda el plan anterior en
-- task_plan_backup_2026_10_09 (ver paso 4 para restaurar).
--
-- PARA OTRA FECHA DE CORTE: cambie '2026-10-10' en el paso 1 y en el paso 2
-- (solo esos dos sitios; el UPDATE trabaja sobre la copia del paso 2).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) VISTA PREVIA (no modifica nada): que se va a limpiar, por dia.
--    Revise estos numeros antes de seguir.
-- ---------------------------------------------------------------------
SELECT t.scheduled_date                      AS dia,
       COUNT(*)                              AS tareas,
       COUNT(*) FILTER (WHERE t.desk IS NOT NULL) AS con_mesa
FROM task t
WHERE t.status = 'PENDING'
  AND t.scheduled_date >= DATE '2026-10-10'
GROUP BY t.scheduled_date
ORDER BY t.scheduled_date;

-- Lo que NO se tocara aunque tenga fecha futura (para que no sorprenda):
SELECT t.status, COUNT(*) AS tareas
FROM task t
WHERE t.scheduled_date >= DATE '2026-10-10'
  AND t.status <> 'PENDING'
GROUP BY t.status;

-- ---------------------------------------------------------------------
-- 2) COPIA DE RESPALDO + LIMPIEZA, en una sola transaccion.
-- ---------------------------------------------------------------------
BEGIN;

CREATE TABLE IF NOT EXISTS task_plan_backup_2026_10_09 (
    task_id        BIGINT PRIMARY KEY,
    desk           INTEGER,
    scheduled_date DATE,
    backed_up_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO task_plan_backup_2026_10_09 (task_id, desk, scheduled_date)
SELECT t.id, t.desk, t.scheduled_date
FROM task t
WHERE t.status = 'PENDING'
  AND t.scheduled_date >= DATE '2026-10-10'
ON CONFLICT (task_id) DO NOTHING;

UPDATE task t
SET desk           = NULL,
    scheduled_date = NULL,
    updated_at     = NOW()
WHERE t.status = 'PENDING'
  AND t.id IN (SELECT task_id FROM task_plan_backup_2026_10_09);

COMMIT;

-- ---------------------------------------------------------------------
-- 3) COMPROBACION (no modifica nada). Debe devolver 0 filas.
-- ---------------------------------------------------------------------
SELECT t.id, t.code, t.status, t.desk, t.scheduled_date
FROM task t
WHERE t.status = 'PENDING'
  AND t.scheduled_date >= DATE '2026-10-10';

-- ---------------------------------------------------------------------
-- 4) DESHACER (solo si hace falta). Devuelve cada tarea a su mesa y dia
--    anteriores, y solo si siguen sin planificar (no pisa lo que se haya
--    planificado despues de la limpieza). Descomentar para usar.
-- ---------------------------------------------------------------------
-- UPDATE task t
-- SET desk           = b.desk,
--     scheduled_date = b.scheduled_date,
--     updated_at     = NOW()
-- FROM task_plan_backup_2026_10_09 b
-- WHERE b.task_id = t.id
--   AND t.status = 'PENDING'
--   AND t.scheduled_date IS NULL
--   AND t.desk IS NULL;
