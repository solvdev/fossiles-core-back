-- Quita shipment_number_counter creado por scripts/shipment-number-sequence.sql.
-- ORDEN DE DESPLIEGUE: revertir la app (PR #184) primero, luego COMMIT este rollback.
-- El seed (shipment-number-sequence.sql) se corre ANTES de desplegar #184 (ddl-auto=validate).
-- DataGrip: corre el archivo completo. Por defecto termina en ROLLBACK.
-- Para aplicar, cambie el ROLLBACK final por COMMIT.
-- No toca product_shipment ni production_order.

BEGIN;
SET LOCAL lock_timeout = '5s';

SELECT 'PREVIEW_DROP' AS seccion, type, last_value
FROM shipment_number_counter
WHERE type IN ('LF_ENVP', 'LF_OPC')
ORDER BY type;

DROP TABLE IF EXISTS shipment_number_counter;

-- Vista previa: cambie a COMMIT para aplicar.
ROLLBACK;
