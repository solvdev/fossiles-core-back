-- Diagnóstico (solo lectura): devoluciones por boleta de cambio que ingresaron a la fila NUEVO
-- aunque la venta original salió de otra fila (p. ej. VIEJO).
--
-- Causa: kiosk_exchange_slip_returned_item no guarda herraje y el ingreso del cambio caía en NUEVO
-- por defecto. Corregido en KioskExchangeService.resolveReturnedHardware (hotfix/cambio-devuelto-herraje).
--
-- Efecto: la pieza física es VIEJO pero el sistema la suma a NUEVO; si luego se vende como NUEVO,
-- la fila VIEJO sigue mostrando stock que ya no existe y la NUEVO queda "rebajada" de más.

WITH devueltos AS (
    SELECT s.slip_number,
           s.status,
           s.kiosk_location_id,
           l.name                 AS kiosko,
           p.code                 AS producto,
           c.name                 AS color,
           ri.product_id,
           ri.color_id,
           si.kiosk_sale_id       AS venta_original_id,
           rm.id                  AS movimiento_ingreso_id,
           rm.created_at          AS ingreso_at,
           rks.id                 AS stock_ingreso_id,
           rks.hardware_condition AS herraje_ingreso,
           (SELECT string_agg(DISTINCT oks.hardware_condition, ',')
              FROM kiosco_movement om
              JOIN kiosco_stock oks ON oks.id = om.kiosco_stock_id
             WHERE om.reference_id = si.kiosk_sale_id
               AND om.movement_type IN ('VENTA', 'SALIDA')
               AND oks.location_id = s.kiosk_location_id
               AND oks.product_id = ri.product_id
               AND oks.color_id IS NOT DISTINCT FROM ri.color_id) AS herraje_vendido
      FROM kiosk_exchange_slip_returned_item ri
      JOIN kiosk_exchange_slip s ON s.id = ri.exchange_slip_id
      JOIN kiosco_movement rm ON rm.id = ri.return_movement_id
      JOIN kiosco_stock rks ON rks.id = rm.kiosco_stock_id
      JOIN locations l ON l.id = s.kiosk_location_id
      JOIN product p ON p.id = ri.product_id
      LEFT JOIN colors c ON c.id = ri.color_id
      LEFT JOIN kiosk_sale_item si ON si.id = ri.original_sale_item_id
)
SELECT d.slip_number,
       d.kiosko,
       d.producto,
       d.color,
       d.ingreso_at,
       d.herraje_vendido,
       d.herraje_ingreso,
       d.stock_ingreso_id,
       vk.id              AS stock_correcto_id,
       d_stock.current_stock AS stock_actual_ingreso,
       vk.current_stock   AS stock_actual_correcto,
       -- Ventas posteriores desde la fila equivocada (la pieza "viejo" vendida como "nuevo").
       (SELECT count(*)
          FROM kiosco_movement vm
         WHERE vm.kiosco_stock_id = d.stock_ingreso_id
           AND vm.movement_type IN ('VENTA', 'SALIDA', 'CAMBIO')
           AND vm.stock_after < vm.stock_before
           AND vm.created_at > d.ingreso_at) AS egresos_posteriores_fila_ingreso
  FROM devueltos d
  JOIN kiosco_stock d_stock ON d_stock.id = d.stock_ingreso_id
  LEFT JOIN kiosco_stock vk
         ON vk.location_id = d.kiosk_location_id
        AND vk.product_id = d.product_id
        AND vk.color_id IS NOT DISTINCT FROM d.color_id
        AND vk.hardware_condition = split_part(d.herraje_vendido, ',', 1)
 WHERE d.herraje_vendido IS NOT NULL
   AND position(d.herraje_ingreso IN d.herraje_vendido) = 0
 ORDER BY d.ingreso_at DESC;
