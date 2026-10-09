-- OPCIONAL (solo para que la base quede coherente).
-- Desde 2026-10-01 la comision de venta es FIJA en 4 % en el codigo: el calculo, la pantalla de costos, los reportes
-- y los Excel ya NO leen kiosk_period_config.sales_commission_pct, asi que lo que quede guardado ahi no afecta nada.
-- Este script solo pone 4 % en todas las filas para que no haya valores viejos confusos al consultar la tabla.

UPDATE kiosk_period_config
SET sales_commission_pct = 0.04, updated_at = NOW()
WHERE sales_commission_pct IS DISTINCT FROM 0.04;

-- Verificar: debe devolver 0
SELECT COUNT(*) FROM kiosk_period_config
WHERE sales_commission_pct IS DISTINCT FROM 0.04;
