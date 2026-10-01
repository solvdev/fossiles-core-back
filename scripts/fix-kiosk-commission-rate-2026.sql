-- Corrige la tasa de comision de venta ya importada de los reportes 2026 (formato nuevo).
-- Antes se deducia del monto del Excel (monto / ventas): daba 0 a los kioscos que no llegaban al 70 % y tasas
-- erroneas (p. ej. 0.31 % en Miraflores II, septiembre 2026) cuando el Excel traia un monto raro.
-- Ahora el sistema aplica el 70 % al calcular, asi que la tasa guardada debe ser la nominal (4 %).
-- Ejecutar a mano. Solo toca filas importadas de Excel (source = 'EXCEL'); lo editado a mano no se modifica.

-- 1) Revisar antes de cambiar (que tasas distintas de 4 % hay en 2026):
SELECT s.name, c.month, c.sales_commission_pct, c.source
FROM kiosk_period_config c
JOIN kiosk_site s ON s.id = c.site_id
WHERE c.year = 2026 AND c.source = 'EXCEL'
  AND c.sales_commission_pct IS NOT NULL AND c.sales_commission_pct <> 0.04
ORDER BY c.month, s.name;

-- 2) Corregir
UPDATE kiosk_period_config
SET sales_commission_pct = 0.04, updated_at = NOW()
WHERE year = 2026 AND source = 'EXCEL'
  AND sales_commission_pct IS NOT NULL AND sales_commission_pct <> 0.04;
