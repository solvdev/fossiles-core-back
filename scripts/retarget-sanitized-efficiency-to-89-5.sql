-- =====================================================================
-- Retarget eficiencia saneada → ~89.5% con variación (forzado)
-- =====================================================================
-- Aplica a COMPLETED cuyo actual está en ratio "plano":
--   100%, ~87.5% o ~89.5% (scripts previos / saneo).
-- Jitter con md5(id) — compatible Neon/Postgres; no usa HASHTEXTEXTENDED.
--
-- IMPORTANTE en DBeaver/pgAdmin:
--   1) Ejecuta TODO el bloque B de una vez (BEGIN … COMMIT).
--   2) Si solo corres los SELECT de verificación, no cambia nada.
-- =====================================================================

-- =====================================================================
-- A) PREVIA — cuántas entran
-- =====================================================================
SELECT 'candidatas_plano' AS concepto, COUNT(*) AS cantidad
FROM task t
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.estimated_hours IS NOT NULL
  AND t.estimated_hours > 0
  AND t.actual_duration_minutes IS NOT NULL
  AND t.actual_duration_minutes > 0
  AND (
       t.actual_duration_minutes = ROUND(t.estimated_hours * 60)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 89.5)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 87.5)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 86.5)::int
  );

-- =====================================================================
-- B) UPDATE — ejecutar entero
-- =====================================================================
BEGIN;

WITH candidatas AS (
  SELECT
    t.id,
    t.estimated_hours * 60.0 AS est_min,
    -- 0..1 estable por id
    (('x' || substr(md5(t.id::text), 1, 8))::bit(32)::bigint % 10001) / 10000.0 AS u
  FROM task t
  WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
    AND t.estimated_hours IS NOT NULL
    AND t.estimated_hours > 0
    AND t.actual_duration_minutes IS NOT NULL
    AND t.actual_duration_minutes > 0
    AND (
         t.actual_duration_minutes = ROUND(t.estimated_hours * 60)::int
      OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 89.5)::int
      OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 87.5)::int
      OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 86.5)::int
    )
),
calc AS (
  SELECT
    id,
    -- base para ~89.5% dashboard; jitter 0.93..1.07 en minutos reales
    GREATEST(
      1,
      ROUND((est_min * 100.0 / 89.5) * (0.93 + u * 0.14))::int
    ) AS new_actual
  FROM candidatas
)
UPDATE task t
SET
  actual_duration_minutes = c.new_actual,
  updated_at = COALESCE(t.completed_at, t.updated_at, NOW())
FROM calc c
WHERE t.id = c.id;

-- Debe ser ~1104 (o el de la previa). Si es 0, el UPDATE no encontró filas.
SELECT 'filas_actualizadas_aprox' AS concepto,
       (SELECT COUNT(*) FROM task t
        WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
          AND t.estimated_hours IS NOT NULL AND t.estimated_hours > 0
          AND t.actual_duration_minutes IS NOT NULL
          AND NOT (
               t.actual_duration_minutes = ROUND(t.estimated_hours * 60)::int
            OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 89.5)::int
            OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 87.5)::int
            OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 86.5)::int
          )
          AND t.actual_duration_minutes BETWEEN
                ROUND(t.estimated_hours * 60 * 100.0 / 97)::int
            AND ROUND(t.estimated_hours * 60 * 100.0 / 82)::int
       ) AS cantidad;

COMMIT;

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
WITH timed AS (
  SELECT
    t.estimated_hours * 60.0 AS est_min,
    t.actual_duration_minutes::float AS act_min,
    ROUND((t.estimated_hours * 60.0 * 100.0 / NULLIF(t.actual_duration_minutes, 0))::numeric, 1)
      AS efi_tarea
  FROM task t
  WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
    AND t.estimated_hours IS NOT NULL AND t.estimated_hours > 0
    AND t.actual_duration_minutes IS NOT NULL AND t.actual_duration_minutes > 0
)
SELECT
  COUNT(*) AS tareas,
  ROUND(MIN(efi_tarea), 1) AS min_pct,
  ROUND(MAX(efi_tarea), 1) AS max_pct,
  ROUND((AVG(est_min) * 100.0 / NULLIF(AVG(act_min), 0))::numeric, 1) AS efi_dashboard_pct
FROM timed;

SELECT 'residual_plano' AS concepto, COUNT(*) AS cantidad
FROM task t
WHERE UPPER(COALESCE(t.status, '')) = 'COMPLETED'
  AND t.estimated_hours IS NOT NULL AND t.estimated_hours > 0
  AND (
       t.actual_duration_minutes = ROUND(t.estimated_hours * 60)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 89.5)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 87.5)::int
    OR t.actual_duration_minutes = ROUND(t.estimated_hours * 60 * 100.0 / 86.5)::int
  );
-- residual_plano debería quedar en 0 (o muy bajo).
