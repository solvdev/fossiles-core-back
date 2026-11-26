-- Corrige las visitas del calendario de conteos que quedaron mal marcadas como «Revisita» (o al revés).
-- Termina en ROLLBACK: revisar las filas que devuelve y, si están bien, cambiar la última línea por COMMIT.

BEGIN;

-- 1) Marcadas «Revisita» sin tener antes las visitas que le tocan al kiosco en el mes.
WITH counts AS (
    SELECT location_id, period_to FROM kiosco_physical_count WHERE status <> 'DRAFT'
    UNION
    SELECT location_id, period_to FROM kiosk_count_history
), stale AS (
    SELECT v.id
    FROM kiosk_count_visit v
    LEFT JOIN kiosk_count_visit_config c ON c.location_id = v.location_id
    CROSS JOIN LATERAL (SELECT COUNT(*) AS n FROM kiosk_count_visit p
                        WHERE p.location_id = v.location_id AND p.visit_month = v.visit_month
                          AND p.status <> 'CANCELLED' AND p.day_seq = 1 AND p.visit_date < v.visit_date) earlier
    CROSS JOIN LATERAL (SELECT COUNT(*) AS n FROM counts k
                        WHERE k.location_id = v.location_id AND to_char(k.period_to, 'YYYY-MM') = v.visit_month
                          AND k.period_to < v.visit_date - 1) counted
    WHERE v.kind = 'REVISIT' AND v.status <> 'CANCELLED'
      AND earlier.n < COALESCE(c.visits_per_month, 1)
      AND earlier.n + counted.n < COALESCE(c.visits_per_month, 1)
)
UPDATE kiosk_count_visit v SET kind = 'REGULAR', updated_at = NOW()
FROM stale, locations l
WHERE v.id = stale.id AND l.id = v.location_id
RETURNING v.visit_date, l.name, v.kind;

-- 2) Marcadas como visita normal cuando el kiosco ya tiene antes todas las del mes.
UPDATE kiosk_count_visit v SET kind = 'REVISIT', updated_at = NOW()
FROM locations l
WHERE l.id = v.location_id AND v.kind = 'REGULAR' AND v.status <> 'CANCELLED' AND v.day_seq = 1
  AND (SELECT COUNT(*) FROM kiosk_count_visit p
       WHERE p.location_id = v.location_id AND p.visit_month = v.visit_month
         AND p.status <> 'CANCELLED' AND p.day_seq = 1 AND p.visit_date < v.visit_date)
      >= COALESCE((SELECT c.visits_per_month FROM kiosk_count_visit_config c WHERE c.location_id = v.location_id), 1)
RETURNING v.visit_date, l.name, v.kind;

ROLLBACK;
