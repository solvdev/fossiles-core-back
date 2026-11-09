-- Renumera las ventas en línea existentes con el correlativo del MES (1, 2, 3… y vuelve a 1 cada mes),
-- igual que la columna «No.» del Excel de ventas. Orden dentro del mes: fecha de venta y luego id (orden de captura).
--
-- CUÁNDO: justo DESPUÉS de desplegar el backend que numera por mes (OnlineSaleService.nextMonthlySaleNumber).
-- Si se corre con el backend viejo, las ventas nuevas del día volverían a salir con 1, 2, 3… y chocarían.
-- Correr primero en fosstest, luego en fossilesgt.
--
-- Efectos: cambia el «#pedido» que ya salió impreso en hojas de envío y en textos de movimientos
-- («Despacho venta online #12»); esos textos no se reescriben. El ENVL no cambia.
--
-- Deshacer (mientras exista el respaldo):
--   UPDATE online_sale s SET sale_number = b.sale_number
--   FROM online_sale_number_backup_20261109 b WHERE b.id = s.id;

BEGIN;

-- 1) Respaldo de los números actuales (no se pisa si ya existe).
CREATE TABLE IF NOT EXISTS online_sale_number_backup_20261109 AS
SELECT id, sale_date, sale_number, NOW() AS backed_up_at
FROM online_sale;

-- 2) Renumerar por mes.
WITH numbered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY date_trunc('month', sale_date) ORDER BY sale_date, id) AS n
    FROM online_sale
    WHERE sale_date IS NOT NULL
)
UPDATE online_sale s
SET sale_number = numbered.n::text
FROM numbered
WHERE numbered.id = s.id
  AND s.sale_number IS DISTINCT FROM numbered.n::text;

-- 3) Comprobación: si hay números repetidos dentro de un mes, se cancela todo.
DO $$
DECLARE
    dup INT;
BEGIN
    SELECT COUNT(*) INTO dup FROM (
        SELECT 1 FROM online_sale
        WHERE sale_date IS NOT NULL
        GROUP BY date_trunc('month', sale_date), sale_number
        HAVING COUNT(*) > 1
    ) d;
    IF dup > 0 THEN
        RAISE EXCEPTION 'Renumeración cancelada: % números repetidos dentro de un mes', dup;
    END IF;
END $$;

COMMIT;

-- 4) Resumen por mes (solo lectura).
SELECT to_char(date_trunc('month', sale_date), 'YYYY-MM') AS mes,
       COUNT(*) AS ventas,
       MIN(sale_number::int) AS primero,
       MAX(sale_number::int) AS ultimo
FROM online_sale
WHERE sale_date IS NOT NULL
GROUP BY 1
ORDER BY 1;
