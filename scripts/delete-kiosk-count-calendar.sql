-- Borra el CALENDARIO DE VISITAS que se generó (Contabilidad > Calendario > Visitas de conteo).
--
-- Borra:     kiosk_count_visit (las visitas: generadas, movidas a mano, revisitas y hechas)
--            kiosk_count_week  (qué semanas ya se generaron; sin esto el sistema las daría por generadas)
-- NO borra:  kiosk_count_history (historial de conteos del Excel: es el «último conteo» de cada kiosco)
--            kiosco_physical_count (los conteos físicos del sistema)
--            kiosk_liquidation (las liquidaciones; ver el aviso de abajo)
--            kiosk_count_visit_config (reglas por kiosco: Miraflores, Entrecueros, Celajes, Naranjo)
--            kiosk_count_blocked_day (días no hábiles que se agregaron a mano)
--
-- Después de borrar, las semanas vuelven a poder generarse desde «Generar esta semana».
--
-- AVISO: las liquidaciones nuevas nacen de las visitas HECHAS del calendario. Borrar visitas hechas hace que los
-- conteos que aún no tienen fecha de liquidación dejen de aparecer como pendientes (las liquidaciones que ya tienen
-- fecha se quedan). Si solo quieres rehacer lo que viene, usa la variante «solo lo que no ha pasado».
--
-- Por seguridad termina en ROLLBACK: corre todo, revisa los números de «ANTES» y «DESPUÉS» y, si es lo que
-- esperas, cambia la última línea a COMMIT y vuelve a correrlo.

BEGIN;

-- ANTES
SELECT 'visitas' AS dato, COUNT(*) AS filas FROM kiosk_count_visit
UNION ALL SELECT 'semanas generadas', COUNT(*) FROM kiosk_count_week
UNION ALL SELECT 'historial de conteos (se conserva)', COUNT(*) FROM kiosk_count_history
UNION ALL SELECT 'liquidaciones (se conservan)', COUNT(*) FROM kiosk_liquidation;

SELECT visit_month AS mes, status AS estado, kind AS tipo, manual AS a_mano, COUNT(*) AS filas
FROM kiosk_count_visit GROUP BY visit_month, status, kind, manual ORDER BY 1, 2, 3, 4;

-- BORRADO (todo el calendario generado)
DELETE FROM kiosk_count_visit;
DELETE FROM kiosk_count_week;

-- Variantes (usa una de estas en lugar del borrado de arriba):
--
--   Solo lo que no ha pasado (conserva lo hecho, lo movido a mano y las revisitas):
--     DELETE FROM kiosk_count_visit
--      WHERE visit_date > CURRENT_DATE AND status = 'PLANNED' AND manual = FALSE AND kind = 'REGULAR';
--     DELETE FROM kiosk_count_week WHERE week_start > date_trunc('week', CURRENT_DATE)::date;
--
--   Solo un mes (cambia el mes):
--     DELETE FROM kiosk_count_visit WHERE visit_month = '2026-12';
--     DELETE FROM kiosk_count_week WHERE week_start >= DATE '2026-11-30' AND week_start <= DATE '2026-12-28';
--
--   Solo lo pendiente (conserva lo marcado como hecho):
--     DELETE FROM kiosk_count_visit WHERE status = 'PLANNED';
--     DELETE FROM kiosk_count_week;
--
--   Solo una supervisora (cambia el id; se ven en kiosk_supervisor_assignment):
--     DELETE FROM kiosk_count_visit WHERE supervisor_user_id = 63;
--     DELETE FROM kiosk_count_week WHERE supervisor_user_id = 63;
--
--   Además, quitar los días no hábiles agregados a mano:
--     DELETE FROM kiosk_count_blocked_day;

-- DESPUÉS
SELECT 'visitas' AS dato, COUNT(*) AS filas FROM kiosk_count_visit
UNION ALL SELECT 'semanas generadas', COUNT(*) FROM kiosk_count_week
UNION ALL SELECT 'historial de conteos (se conserva)', COUNT(*) FROM kiosk_count_history
UNION ALL SELECT 'liquidaciones (se conservan)', COUNT(*) FROM kiosk_liquidation;

ROLLBACK;   -- ← cambiar a COMMIT cuando los números de arriba sean los esperados
