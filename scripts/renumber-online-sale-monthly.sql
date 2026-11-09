-- OPCIONAL: renumera las ventas en línea ya existentes con el correlativo del MES (1, 2, 3… por mes),
-- igual que la columna «No.» del Excel de ventas. El backend nuevo ya numera así las ventas nuevas.
-- Orden dentro del mes: fecha de venta y luego id (orden de captura).
--
-- OJO: cambia el «#pedido» que ya se imprimió en hojas de envío y que aparece en textos de movimientos
-- («Despacho venta online #12»). Conviene correrlo solo desde un mes cerrado, por ejemplo:
--   AND sale_date >= DATE '2026-03-01'
-- Respaldo antes de correr:
--   CREATE TABLE online_sale_number_backup_20261109 AS SELECT id, sale_date, sale_number FROM online_sale;

BEGIN;

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

-- Revisión: no debe haber números repetidos dentro de un mes.
SELECT date_trunc('month', sale_date) AS mes, sale_number, COUNT(*)
FROM online_sale
GROUP BY 1, 2
HAVING COUNT(*) > 1;

-- COMMIT;  -- descomentar después de revisar; si algo no cuadra: ROLLBACK;
