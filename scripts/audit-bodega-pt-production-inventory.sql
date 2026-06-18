-- Auditoría: inventario Bodega PT (producto terminado) vs órdenes de producción
-- Ejecutar en PostgreSQL contra la BD de Fossiles.
--
-- Flujo esperado (código actual):
--   1) Crear OP (cualquier tipo) -> NO suma stock en BODEGA_PT (warehouse_received_qty = 0).
--   2) Producción / tareas -> NO suma stock PT (solo consume materiales en cinchos gestionados).
--   3) Recepción bodega PT (pieza a pieza) -> PRODUCTION_ENTRY en kardex + incremento en product_inventory_location.
--   4) Despacho (distribución SENT, venta online desde PT) -> salida SHIPMENT / decremento PT.
--
-- Ubicación BODEGA_PT: location cuyo code = 'BODEGA_PT' (tipo inventory_location_type).

-- =============================================================================
-- 0) Resolver location_id de Bodega PT
-- =============================================================================
-- SELECT id, code, name FROM locations WHERE upper(code) = 'BODEGA_PT';

-- =============================================================================
-- 1) Entradas por producción (PRODUCTION_ENTRY) en Bodega PT — últimos 90 días
-- =============================================================================
SELECT
    k.id AS kardex_id,
    k.movement_date,
    k.movement_type,
    k.reference_type,
    k.reference_id,
    k.reference_number,
    p.code AS product_code,
    p.name AS product_name,
    c.name AS color_name,
    k.quantity,
    k.quantity_before,
    k.quantity_after,
    k.description
FROM product_inventory_kardex k
JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
JOIN product p ON p.id = k.product_id
LEFT JOIN colors c ON c.id = k.color_id
WHERE k.movement_type = 'PRODUCTION_ENTRY'
  AND k.movement_date >= now() - interval '90 days'
ORDER BY k.movement_date DESC, k.id DESC;

-- =============================================================================
-- 2) OP con ingreso a bodega pero estado aún PENDING (posible recepción prematura)
-- =============================================================================
SELECT
    po.id,
    po.code,
    po.order_type,
    po.status,
    po.created_at,
    po.warehouse_receipt_closed_at,
    poi.id AS item_id,
    p.code AS product_code,
    poi.quantity AS planned_qty,
    coalesce(poi.warehouse_received_qty, 0) AS received_qty,
    (SELECT count(*) FROM production_order_warehouse_unit u
     WHERE u.production_order_item_id = poi.id AND u.receipt_status = 'RECEIVED') AS units_received
FROM production_order po
JOIN production_order_item poi ON poi.production_order_id = po.id
JOIN product p ON p.id = poi.product_id
WHERE coalesce(poi.warehouse_received_qty, 0) > 0
  AND upper(coalesce(po.status, '')) = 'PENDING'
ORDER BY po.created_at DESC;

-- =============================================================================
-- 3) Doble vía de recepción en la misma OP (legacy ítem + piezas PT)
--    Legacy: reference_type PRODUCTION_ORDER_ITEM en kardex
--    Actual: reference_type PRODUCTION_ORDER_WH_UNIT en kardex
-- =============================================================================
SELECT
    po.id,
    po.code,
    po.order_type,
    coalesce(legacy.legacy_qty, 0) AS legacy_production_entry_qty,
    coalesce(units.unit_qty, 0) AS unit_production_entry_qty,
    coalesce(legacy.legacy_qty, 0) + coalesce(units.unit_qty, 0) AS total_entry_qty,
    (SELECT sum(coalesce(poi.quantity, 0)) FROM production_order_item poi WHERE poi.production_order_id = po.id) AS planned_qty
FROM production_order po
LEFT JOIN (
    SELECT poi.production_order_id, sum(k.quantity) AS legacy_qty
    FROM product_inventory_kardex k
    JOIN production_order_item poi ON poi.id = k.reference_id
    JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
    WHERE k.movement_type = 'PRODUCTION_ENTRY'
      AND k.reference_type = 'PRODUCTION_ORDER_ITEM'
    GROUP BY poi.production_order_id
) legacy ON legacy.production_order_id = po.id
LEFT JOIN (
    SELECT u.production_order_id, sum(k.quantity) AS unit_qty
    FROM product_inventory_kardex k
    JOIN production_order_warehouse_unit u ON u.id = k.reference_id
    JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
    WHERE k.movement_type = 'PRODUCTION_ENTRY'
      AND k.reference_type = 'PRODUCTION_ORDER_WH_UNIT'
    GROUP BY u.production_order_id
) units ON units.production_order_id = po.id
WHERE coalesce(legacy.legacy_qty, 0) > 0
  AND coalesce(units.unit_qty, 0) > 0
ORDER BY po.created_at DESC;

-- =============================================================================
-- 4) Stock PT vs suma de movimientos de kardex (por producto/color)
--    Diferencia != 0 puede indicar ajustes manuales, migraciones o inconsistencia.
-- =============================================================================
WITH bodega AS (
    SELECT id FROM locations WHERE upper(code) = 'BODEGA_PT' LIMIT 1
),
kardex_net AS (
    SELECT
        k.product_id,
        k.color_id,
        sum(k.quantity) AS net_from_kardex
    FROM product_inventory_kardex k
    CROSS JOIN bodega b
    WHERE k.location_id = b.id
    GROUP BY k.product_id, k.color_id
)
SELECT
    p.code AS product_code,
    p.name AS product_name,
    c.name AS color_name,
    pil.quantity AS stock_actual,
    kn.net_from_kardex,
    pil.quantity - kn.net_from_kardex AS diff
FROM product_inventory_location pil
CROSS JOIN bodega b
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
LEFT JOIN kardex_net kn ON kn.product_id = pil.product_id
    AND ((kn.color_id IS NULL AND pil.color_id IS NULL) OR kn.color_id = pil.color_id)
WHERE pil.location_id = b.id
  AND abs(coalesce(pil.quantity, 0) - coalesce(kn.net_from_kardex, 0)) > 0.001
ORDER BY abs(pil.quantity - coalesce(kn.net_from_kardex, 0)) DESC;

-- =============================================================================
-- 5) OP recientes: ¿hay PRODUCTION_ENTRY el mismo día que se creó la OP?
--    (No es bug por sí solo, pero ayuda a detectar recepciones sin producción real)
-- =============================================================================
SELECT
    po.id,
    po.code,
    po.order_type,
    po.status,
    po.created_at::date AS op_created,
    min(k.movement_date)::date AS first_pt_entry,
    sum(k.quantity) AS total_pt_entry_qty
FROM production_order po
JOIN production_order_item poi ON poi.production_order_id = po.id
JOIN product_inventory_kardex k ON k.movement_type = 'PRODUCTION_ENTRY'
    AND k.reference_type IN ('PRODUCTION_ORDER_ITEM', 'PRODUCTION_ORDER_WH_UNIT')
    AND (
        (k.reference_type = 'PRODUCTION_ORDER_ITEM' AND k.reference_id = poi.id)
        OR (k.reference_id IN (
            SELECT u.id FROM production_order_warehouse_unit u
            WHERE u.production_order_item_id = poi.id
        ))
    )
JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
WHERE po.created_at >= now() - interval '60 days'
GROUP BY po.id, po.code, po.order_type, po.status, po.created_at
HAVING min(k.movement_date)::date <= po.created_at::date + interval '1 day'
ORDER BY po.created_at DESC;

-- =============================================================================
-- 6) Otras entradas a Bodega PT (no producción): transferencias y ajustes
-- =============================================================================
SELECT
    k.movement_type,
    k.reference_type,
    count(*) AS movimientos,
    sum(k.quantity) AS qty_neta
FROM product_inventory_kardex k
JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
WHERE k.movement_type NOT IN ('PRODUCTION_ENTRY', 'SHIPMENT')
  AND k.quantity > 0
  AND k.movement_date >= now() - interval '90 days'
GROUP BY k.movement_type, k.reference_type
ORDER BY sum(k.quantity) DESC;

-- =============================================================================
-- 7) Salidas desde Bodega PT (despachos / envíos) — últimos 90 días
-- =============================================================================
SELECT
    k.movement_date,
    k.movement_type,
    k.reference_type,
    k.reference_id,
    k.reference_number,
    p.code AS product_code,
    k.quantity,
    k.description
FROM product_inventory_kardex k
JOIN locations l ON l.id = k.location_id AND upper(l.code) = 'BODEGA_PT'
JOIN product p ON p.id = k.product_id
WHERE k.quantity < 0
   OR k.movement_type IN ('SHIPMENT', 'TRANSFER_OUT', 'ADJUSTMENT_OUT', 'SALE_EXIT')
ORDER BY k.movement_date DESC
LIMIT 200;
