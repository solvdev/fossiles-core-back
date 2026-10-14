-- =============================================================================
-- CUADRE DE INVENTARIO DE PRODUCTO TERMINADO (PostgreSQL)
-- =============================================================================
-- Cada consulta devuelve SOLO las filas que NO cuadran. Si una consulta devuelve
-- cero filas, ese proceso está cuadrado. Guía de uso: docs/VERIFICACION-INVENTARIO-PT.md
--
-- Ubicaciones de bodega (product_inventory_location):
--   BODEGA_PT, Devoluciones (BODEGA_DEV / DEVOLUCIONES / ...), EN_TRANSITO, DEV_NO_APTA
-- Kioskos: kiosco_stock / kiosco_movement (no usan product_inventory_location).
--
-- :desde = fecha desde la que se revisa (p.ej. el día del despliegue de estos cambios).
-- En DBeaver/psql reemplace :desde por una fecha, p.ej. '2026-10-15'.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. STOCK vs KARDEX (todas las bodegas)
--    El saldo de cada fila debe ser igual al quantity_after del último movimiento
--    de kardex de esa fila (producto + ubicación + color).
-- -----------------------------------------------------------------------------
WITH ultimo AS (
    SELECT DISTINCT ON (k.product_id, k.location_id, k.color_id)
           k.product_id, k.location_id, k.color_id, k.quantity_after, k.movement_date, k.movement_type,
           k.reference_type, k.reference_number
    FROM product_inventory_kardex k
    ORDER BY k.product_id, k.location_id, k.color_id, k.id DESC
)
SELECT l.code AS ubicacion, p.code AS producto, c.name AS color,
       pil.quantity AS stock_actual, u.quantity_after AS kardex_ultimo_saldo,
       pil.quantity - u.quantity_after AS diferencia,
       u.movement_type AS ultimo_movimiento, u.reference_number, u.movement_date
FROM product_inventory_location pil
JOIN ultimo u ON u.product_id = pil.product_id AND u.location_id = pil.location_id
             AND u.color_id IS NOT DISTINCT FROM pil.color_id
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
WHERE pil.quantity <> u.quantity_after
ORDER BY l.code, p.code;


-- -----------------------------------------------------------------------------
-- 2. STOCK NEGATIVO (nunca debe existir)
-- -----------------------------------------------------------------------------
SELECT l.code AS ubicacion, p.code AS producto, c.name AS color, pil.quantity, pil.sizes_data
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
WHERE pil.quantity < 0
   OR EXISTS (SELECT 1 FROM jsonb_each_text(COALESCE(NULLIF(pil.sizes_data, ''), '{}')::jsonb) t
              WHERE t.value::numeric < 0);


-- -----------------------------------------------------------------------------
-- 3. CINCHOS: la suma por talla debe ser igual al total de la fila
-- -----------------------------------------------------------------------------
SELECT l.code AS ubicacion, p.code AS producto, c.name AS color, pil.quantity AS total,
       s.suma_tallas, pil.sizes_data
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(t.value::numeric), 0) AS suma_tallas
    FROM jsonb_each_text(COALESCE(NULLIF(pil.sizes_data, ''), '{}')::jsonb) t
) s
WHERE pil.sizes_data IS NOT NULL AND pil.sizes_data NOT IN ('', '{}')
  AND s.suma_tallas <> pil.quantity;


-- -----------------------------------------------------------------------------
-- 4. CINCHOS SIN TALLA en documentos (no debe haber ninguno nuevo)
-- -----------------------------------------------------------------------------
-- 4a. Líneas de envío
SELECT s.shipment_number, s.status, p.code AS producto, d.quantity, s.created_at
FROM product_shipment_detail d
JOIN product_shipment s ON s.id = d.shipment_id
JOIN product p ON p.id = d.product_id
WHERE COALESCE(TRIM(d.size_label), '') = ''
  AND s.status IN ('CONFIRMED', 'SENT', 'DELIVERED')
  AND p.code NOT ILIKE 'SUM-%'
  AND (p.code ILIKE 'FOSS%' OR COALESCE(p.cincho_type, '') <> '' OR p.name ILIKE '%cincho%')
  AND s.created_at >= :desde;

-- 4b. Traslados entre bodegas
SELECT t.id, t.physical_slip_number, p.code AS producto, t.quantity, t.created_at
FROM inventory_transfer t
JOIN product p ON p.id = t.product_id
WHERE COALESCE(TRIM(t.size_label), '') = ''
  AND p.code NOT ILIKE 'SUM-%'
  AND (p.code ILIKE 'FOSS%' OR COALESCE(p.cincho_type, '') <> '' OR p.name ILIKE '%cincho%')
  AND t.created_at >= :desde;


-- -----------------------------------------------------------------------------
-- 5. ENVÍOS A KIOSKO: salida de bodega = cantidad enviada (por línea)
--    Envíos SENT/DELIVERED con kiosko destino. Neto SHIPMENT + reversiones.
-- -----------------------------------------------------------------------------
SELECT s.shipment_number, s.status, p.code AS producto, d.size_label,
       d.quantity AS enviado,
       COALESCE(-SUM(k.quantity), 0) AS salio_de_bodega
FROM product_shipment s
JOIN product_shipment_detail d ON d.shipment_id = s.id
JOIN product p ON p.id = d.product_id
LEFT JOIN product_inventory_kardex k
       ON k.reference_type = 'SHIPMENT' AND k.reference_id = s.id
      AND k.reference_line_id = d.id
      AND k.movement_type IN ('SHIPMENT', 'SHIPMENT_REVERSAL')
WHERE s.status IN ('SENT', 'DELIVERED')
  AND s.location_id IS NOT NULL
  AND p.code NOT ILIKE 'SUM-%'
  AND s.sent_at >= :desde
GROUP BY s.shipment_number, s.status, p.code, d.size_label, d.quantity
HAVING COALESCE(-SUM(k.quantity), 0) <> d.quantity
ORDER BY s.shipment_number;


-- -----------------------------------------------------------------------------
-- 6. EN TRÁNSITO: saldo en tránsito por línea = lo que falta recibir
--    SENT       -> todo lo enviado
--    DELIVERED  -> enviado - recibido (pendiente de recepción)
--    otro estado (CONFIRMED / CANCELLED) -> 0
-- -----------------------------------------------------------------------------
SELECT s.shipment_number, s.status, p.code AS producto, d.size_label,
       d.quantity AS enviado, d.quantity_received AS recibido,
       CASE WHEN s.status = 'SENT' THEN d.quantity
            WHEN s.status = 'DELIVERED' THEN GREATEST(d.quantity - COALESCE(d.quantity_received, d.quantity), 0)
            ELSE 0 END AS esperado_en_transito,
       COALESCE(SUM(k.quantity), 0) AS en_transito
FROM product_shipment s
JOIN product_shipment_detail d ON d.shipment_id = s.id
JOIN product p ON p.id = d.product_id
LEFT JOIN product_inventory_kardex k
       ON k.reference_type = 'SHIPMENT_TRANSIT' AND k.reference_id = s.id AND k.reference_line_id = d.id
WHERE s.location_id IS NOT NULL
  AND p.code NOT ILIKE 'SUM-%'
  AND s.sent_at >= :desde
GROUP BY s.shipment_number, s.status, p.code, d.size_label, d.quantity, d.quantity_received
HAVING COALESCE(SUM(k.quantity), 0) <>
       CASE WHEN s.status = 'SENT' THEN d.quantity
            WHEN s.status = 'DELIVERED' THEN GREATEST(d.quantity - COALESCE(d.quantity_received, d.quantity), 0)
            ELSE 0 END
ORDER BY s.shipment_number;

-- 6b. Total en tránsito = suma de pendientes (una fila: debe dar diferencia 0)
SELECT
    (SELECT COALESCE(SUM(pil.quantity), 0)
       FROM product_inventory_location pil JOIN locations l ON l.id = pil.location_id
      WHERE l.code = 'EN_TRANSITO') AS stock_en_transito,
    (SELECT COALESCE(SUM(k.quantity), 0)
       FROM product_inventory_kardex k WHERE k.reference_type = 'SHIPMENT_TRANSIT'
        AND k.movement_type IN ('TRANSIT_IN', 'TRANSIT_OUT')) AS saldo_kardex_transito;


-- -----------------------------------------------------------------------------
-- 7. RECEPCIÓN EN KIOSKO: entradas al kiosko = cantidad recibida (por envío y producto)
-- -----------------------------------------------------------------------------
SELECT s.shipment_number, l.name AS kiosko, p.code AS producto,
       SUM(COALESCE(d.quantity_received, d.quantity)) AS recibido_documento,
       COALESCE(e.entradas, 0) AS entradas_kiosko
FROM product_shipment s
JOIN product_shipment_detail d ON d.shipment_id = s.id
JOIN product p ON p.id = d.product_id
JOIN locations l ON l.id = s.location_id
LEFT JOIN LATERAL (
    SELECT SUM(m.quantity) AS entradas
    FROM kiosco_movement m
    JOIN kiosco_stock ks ON ks.id = m.kiosco_stock_id
    WHERE m.reference_id = s.id AND m.movement_type = 'ENTRADA'
      AND ks.location_id = s.location_id AND ks.product_id = d.product_id
) e ON TRUE
WHERE s.status = 'DELIVERED'
  AND s.received_at >= :desde
  AND p.code NOT ILIKE 'SUM-%'
GROUP BY s.shipment_number, l.name, p.code, e.entradas
HAVING SUM(COALESCE(d.quantity_received, d.quantity)) <> COALESCE(e.entradas, 0)
ORDER BY s.shipment_number;


-- -----------------------------------------------------------------------------
-- 8. KIOSKO: stock actual = último saldo de sus movimientos
-- -----------------------------------------------------------------------------
WITH ultimo AS (
    SELECT DISTINCT ON (m.kiosco_stock_id) m.kiosco_stock_id, m.stock_after, m.movement_type, m.created_at
    FROM kiosco_movement m
    WHERE m.affects_stock = TRUE
    ORDER BY m.kiosco_stock_id, m.id DESC
)
SELECT l.name AS kiosko, p.code AS producto, ks.hardware_condition, ks.current_stock, u.stock_after,
       u.movement_type AS ultimo_movimiento, u.created_at
FROM kiosco_stock ks
JOIN ultimo u ON u.kiosco_stock_id = ks.id
JOIN locations l ON l.id = ks.location_id
JOIN product p ON p.id = ks.product_id
WHERE ks.current_stock <> u.stock_after
ORDER BY l.name, p.code;


-- -----------------------------------------------------------------------------
-- 9. DEVOLUCIÓN DEL KIOSKO A BODEGA: cada salida del kiosko entra a bodega
--    (Devoluciones si apto, DEV_NO_APTA si dañado). Solo desde el despliegue.
-- -----------------------------------------------------------------------------
SELECT m.id AS movimiento_kiosko, l.name AS kiosko, p.code AS producto, m.quantity,
       m.physical_slip_number, m.created_at
FROM kiosco_movement m
JOIN kiosco_stock ks ON ks.id = m.kiosco_stock_id
JOIN locations l ON l.id = ks.location_id
JOIN product p ON p.id = ks.product_id
WHERE m.movement_type = 'DEVOLUCION_DEPOSITO'
  AND m.created_at >= :desde
  AND m.destination_location_id IS NULL          -- las de traslados de inventario ya cargan su destino
  AND NOT EXISTS (
      SELECT 1 FROM product_inventory_kardex k
      WHERE k.reference_type = 'KIOSK_DEPOSIT_RETURN' AND k.reference_id = m.id
  );


-- -----------------------------------------------------------------------------
-- 10. VENTAS EN LÍNEA despachadas: lo vendido salió de bodega
--     Ventas ENVIADO/ENTREGADO con producto que no tienen salida neta de PT.
-- -----------------------------------------------------------------------------
SELECT os.sale_number, os.status, os.updated_at
FROM online_sale os
WHERE os.status IN ('ENVIADO', 'ENTREGADO')
  AND os.updated_at >= :desde
  AND EXISTS (SELECT 1 FROM online_sale_item i WHERE i.online_sale_id = os.id AND i.product_id IS NOT NULL)
  AND COALESCE((
      SELECT SUM(k.quantity) FROM product_inventory_kardex k
      WHERE k.reference_id = os.id
        AND k.reference_type IN ('ONLINE_SALE_PREPARE', 'ONLINE_SALE_DISPATCH')
  ), 0) >= 0;


-- -----------------------------------------------------------------------------
-- 11. DEVOLUCIONES DE VENTA EN LÍNEA: lo devuelto entró a bodega (por línea)
-- -----------------------------------------------------------------------------
SELECT os.sale_number, i.product_code, i.size, i.quantity AS devuelto,
       COALESCE(SUM(k.quantity), 0) AS entro_a_bodega
FROM online_sale os
JOIN online_sale_item i ON i.online_sale_id = os.id
LEFT JOIN product_inventory_kardex k
       ON k.reference_type = 'ONLINE_SALE_RETURN' AND k.reference_id = os.id
      AND k.reference_line_id = i.id AND k.movement_type = 'RETURN'
WHERE os.status = 'DEVOLUCION'
  AND os.updated_at >= :desde
  AND i.product_id IS NOT NULL
GROUP BY os.sale_number, i.product_code, i.size, i.quantity
HAVING COALESCE(SUM(k.quantity), 0) <> i.quantity;


-- -----------------------------------------------------------------------------
-- 12. PRODUCCIÓN → BODEGA PT: cada pieza recibida tiene su entrada (PRODUCTION_ENTRY)
-- -----------------------------------------------------------------------------
SELECT po.code AS orden, u.unit_label, u.received_at
FROM production_order_warehouse_unit u
JOIN production_order po ON po.id = u.production_order_id
WHERE u.receipt_status = 'RECEIVED'
  AND u.received_at >= :desde
  AND NOT EXISTS (
      SELECT 1 FROM product_inventory_kardex k
      WHERE k.reference_type = 'PRODUCTION_ORDER_WH_UNIT' AND k.reference_id = u.id
        AND k.movement_type = 'PRODUCTION_ENTRY'
  );

-- 12b. Recibido en el ítem = piezas recibidas
SELECT po.code AS orden, i.id AS item, i.warehouse_received_qty,
       COUNT(u.id) FILTER (WHERE u.receipt_status = 'RECEIVED') AS piezas_recibidas
FROM production_order_item i
JOIN production_order po ON po.id = i.production_order_id
JOIN production_order_warehouse_unit u ON u.production_order_item_id = i.id
GROUP BY po.code, i.id, i.warehouse_received_qty
HAVING COALESCE(i.warehouse_received_qty, 0) <> COUNT(u.id) FILTER (WHERE u.receipt_status = 'RECEIVED');


-- -----------------------------------------------------------------------------
-- 13. RESUMEN: existencias por ubicación (para comparar con el conteo físico)
-- -----------------------------------------------------------------------------
SELECT l.code AS ubicacion, l.name, SUM(pil.quantity) AS unidades, COUNT(*) AS filas
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
GROUP BY l.code, l.name
ORDER BY l.code;
