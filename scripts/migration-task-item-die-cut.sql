-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).
-- Troquelado por producto: mueve la marca de corte del nivel TAREA al nivel PRODUCTO.
--
-- POR QUE
-- Una orden puede bajar a mesa a medias: si de cinco productos hay tres cortados, esos tres
-- se producen y los otros dos esperan. Con la marca en la tarea eso no se puede expresar,
-- porque la tarea esta troquelada o no lo esta.
--
-- El flag de la tarea (task.die_cut_ready) NO desaparece: pasa a ser el Y-logico de sus
-- productos, igual que ya ocurre con el cuero (areTaskItemsLeatherDelivered).
--
-- ORDEN DE DESPLIEGUE
-- Este script va ANTES del binario. Con ddl-auto=validate, si las columnas no existen el
-- backend no arranca -- no es un fallo en tiempo de peticion, es que no levanta.

-- ---------------------------------------------------------------------------------------
-- 1. Columnas nuevas
--
-- die_cut_ready sigue el molde estricto de day_sale_extra (NOT NULL con default) y no el
-- laxo de leather_delivered (nullable sin default): asi los siete sitios que construyen
-- task_item no tienen que setearlo, y nadie necesita leerlo con Boolean.TRUE.equals(...).
-- ---------------------------------------------------------------------------------------
ALTER TABLE task_item ADD COLUMN IF NOT EXISTS die_cut_ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task_item ADD COLUMN IF NOT EXISTS die_cut_date DATE;
ALTER TABLE task_item ADD COLUMN IF NOT EXISTS die_cut_planned_date DATE;

-- ---------------------------------------------------------------------------------------
-- 2. Relleno inicial -- OBLIGATORIO, no es opcional.
--
-- Hoy existen tareas con task.die_cut_ready = TRUE, algunas ya en mesa y en produccion. Sus
-- task_item naceran en FALSE por el default de arriba, y como el flag de la tarea pasa a
-- calcularse desde ellos, TODA tarea ya troquelada volveria a "sin troquelar" al desplegar:
-- dejarian de repartirse a mesa de un dia para otro.
--
-- Se copia del padre. Es idempotente: al correrlo dos veces la segunda no cambia nada.
-- ---------------------------------------------------------------------------------------
UPDATE task_item ti
SET die_cut_ready = TRUE,
    die_cut_date  = t.die_cut_date
FROM task t
WHERE t.id = ti.task_id
  AND t.die_cut_ready IS TRUE
  AND ti.die_cut_ready IS NOT TRUE;

-- ---------------------------------------------------------------------------------------
-- 3. Indice para el filtro del reparto.
--
-- plan-window necesita saber, dentro del candado de planificacion, si una tarea tiene algun
-- producto sin cortar. Sin indice eso es un recorrido de task_item por cada tarea candidata,
-- justo el N+1 que ya obligo a meter una consulta en lote en ese metodo.
-- ---------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_task_item_die_cut_pending
    ON task_item (task_id)
    WHERE die_cut_ready IS NOT TRUE;

-- ---------------------------------------------------------------------------------------
-- 4. Documentacion de columnas
-- ---------------------------------------------------------------------------------------
COMMENT ON COLUMN task_item.die_cut_ready IS
    'Si este producto ya se troquelo. task.die_cut_ready es el Y-logico de los productos de la tarea.';
COMMENT ON COLUMN task_item.die_cut_date IS
    'Cuando se marco el troquelado. Lo escribe el sistema.';
COMMENT ON COLUMN task_item.die_cut_planned_date IS
    'Para que dia esta previsto troquelar. Distinta de die_cut_date: aquella dice cuando se marco, esta cuando toca.';

-- ---------------------------------------------------------------------------------------
-- 5. Comprobacion (no modifica nada). Deberia devolver 0 filas discrepantes.
-- ---------------------------------------------------------------------------------------
-- SELECT count(*) AS tareas_troqueladas_sin_items_marcados
-- FROM task t
-- WHERE t.die_cut_ready IS TRUE
--   AND EXISTS (SELECT 1 FROM task_item ti WHERE ti.task_id = t.id AND ti.die_cut_ready IS NOT TRUE);

-- ---------------------------------------------------------------------------------------
-- 6. LEGADO: tareas que YA estan en mesa sin troquelar. DECISION DE OPERACION, no tecnica.
--
-- El codigo cierra las ocho vias que dan mesa (plan-window, rebalance-day, relleno de mesa
-- liberada, auto-planner, los dos generadores de tareas, fijar mesa a mano y mover producto
-- a mesa), asi que de aqui en adelante no entra a mesa nada sin cortar. Pero lo que ya
-- estaba puesto sigue puesto: ni el reparto ni la redistribucion desasignan, solo dejan de
-- asignar.
--
-- Contarlas antes de decidir, en la base donde se vaya a correr:
--
--   SELECT count(*) FROM task
--   WHERE status = 'PENDING' AND desk IS NOT NULL AND die_cut_ready IS NOT TRUE;
--
-- Hay dos caminos y ninguno es obviamente el correcto:
--
--   a) DEJARLAS. Ya estan agendadas y la gente cuenta con ellas; el troquel las alcanza
--      sobre la marcha. Es lo que pasa si no se corre nada de aqui abajo.
--
--   b) SACARLAS del tablero para que pasen por «Por troquelar» como el resto. Esto las
--      quita de la mesa de un dia para otro: hay que avisar a produccion antes.
--
-- Para (b), descomentar:
--
-- UPDATE task
-- SET desk = NULL
-- WHERE status = 'PENDING'
--   AND desk IS NOT NULL
--   AND die_cut_ready IS NOT TRUE;
