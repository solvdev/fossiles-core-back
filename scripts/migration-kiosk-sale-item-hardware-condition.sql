-- ---------------------------------------------------------------------------
-- Detalle de venta POS: guardar la marca (dimension de stock) de cada linea vendida.
-- Antes la linea solo guardaba el nombre base del producto, por eso en Entrecueros las billeteras
-- salian sin marca en el detalle de la venta. Valores: LEVIS, SINTETICO:LEVIS, NINO, DAMA, NUEVO, VIEJO...
--
-- Ejecutar a mano ANTES de desplegar el backend (hibernate ddl-auto=validate exige la columna).
-- Es idempotente. Revertir: ALTER TABLE kiosk_sale_item DROP COLUMN hardware_condition;
-- ---------------------------------------------------------------------------
ALTER TABLE kiosk_sale_item ADD COLUMN IF NOT EXISTS hardware_condition VARCHAR(40);

COMMENT ON COLUMN kiosk_sale_item.hardware_condition IS
    'Dimension de stock vendida (marca, SINTETICO:MARCA, NINO/DAMA, NUEVO/VIEJO). NULL = venta sin dato';

-- ---------------------------------------------------------------------------
-- Relleno de ventas anteriores desde el kardex (movimientos VENTA ligados a la venta POS).
-- Solo marca / niño-dama (NUEVO y VIEJO no se muestran en Entrecueros, no vale la pena rellenarlos).
-- Una linea se rellena solo si la venta tiene UNA dimension para ese producto+color; si hubo varias
-- marcas del mismo producto+color en la misma venta es ambiguo y se deja en NULL.
-- Las ventas de cambio no mueven stock en el POS, quedan en NULL.
-- ---------------------------------------------------------------------------
WITH sale_hw AS (
    SELECT m.reference_id AS kiosk_sale_id,
           s.product_id,
           s.color_id,
           MIN(s.hardware_condition) AS hardware_condition,
           COUNT(DISTINCT s.hardware_condition) AS hw_count
    FROM kiosco_movement m
    JOIN kiosco_stock s ON s.id = m.kiosco_stock_id
    JOIN kiosk_sale ks ON ks.id = m.reference_id AND ks.kiosk_location_id = s.location_id
    WHERE m.movement_type = 'VENTA'
    GROUP BY m.reference_id, s.product_id, s.color_id
)
UPDATE kiosk_sale_item i
SET hardware_condition = h.hardware_condition
FROM sale_hw h
WHERE i.hardware_condition IS NULL
  AND h.kiosk_sale_id = i.kiosk_sale_id
  AND h.product_id = i.product_id
  AND h.color_id IS NOT DISTINCT FROM i.color_id
  AND h.hw_count = 1
  AND h.hardware_condition NOT IN ('NUEVO', 'VIEJO');

-- Verificacion: lineas con dimension guardada por tipo
SELECT COALESCE(hardware_condition, '(sin dato)') AS hardware_condition, COUNT(*) AS lineas
FROM kiosk_sale_item
GROUP BY 1
ORDER BY 2 DESC;
