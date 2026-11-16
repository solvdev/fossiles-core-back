-- Borra los datos de LIQUIDACIONES del calendario de conteos (Contabilidad > Calendario > Liquidación semanal).
--
-- Borra:     kiosk_liquidation (las liquidaciones, cargadas del Excel, generadas o manuales)
--            kiosk_liquidation_accountant (quién de contabilidad las revisa; se borra en cascada)
-- NO borra:  kiosk_count_history (conteos del historial: son el «último conteo» que usa el generador de visitas)
--            kiosk_count_visit (las visitas del calendario)
--            kiosco_physical_count (los conteos físicos del sistema)
--            kiosk_liquidation_extra_reviewer (revisores adicionales, como Roberto Lique)
--
-- Por seguridad termina en ROLLBACK: corre todo, revisa los números de «ANTES» y «DESPUÉS» y, si es lo que
-- esperas, cambia la última línea a COMMIT y vuelve a correrlo.

BEGIN;

-- ANTES
SELECT 'liquidaciones' AS dato, COUNT(*) AS filas FROM kiosk_liquidation
UNION ALL SELECT 'revisores vinculados', COUNT(*) FROM kiosk_liquidation_accountant
UNION ALL SELECT 'historial de conteos (se conserva)', COUNT(*) FROM kiosk_count_history;

SELECT source AS origen, status AS estado, COUNT(*) AS filas FROM kiosk_liquidation GROUP BY source, status ORDER BY 1, 2;

-- BORRADO (todas las liquidaciones)
DELETE FROM kiosk_liquidation_accountant;
DELETE FROM kiosk_liquidation;

-- Variantes (usa una de estas en lugar del borrado de arriba):
--   Solo lo cargado del Excel:      DELETE FROM kiosk_liquidation WHERE source = 'IMPORT';
--   Solo lo que generó el sistema:  DELETE FROM kiosk_liquidation WHERE source = 'GENERATED';
--   Solo lo pendiente:              DELETE FROM kiosk_liquidation WHERE status = 'PENDING';
-- Para borrar también el historial de conteos cargado del Excel (el generador de visitas dejaría de conocerlo):
--   DELETE FROM kiosk_count_history WHERE source = 'EXCEL';

-- DESPUÉS
SELECT 'liquidaciones' AS dato, COUNT(*) AS filas FROM kiosk_liquidation
UNION ALL SELECT 'revisores vinculados', COUNT(*) FROM kiosk_liquidation_accountant
UNION ALL SELECT 'historial de conteos (se conserva)', COUNT(*) FROM kiosk_count_history;

ROLLBACK;   -- ← cambiar a COMMIT cuando los números de arriba sean los esperados
