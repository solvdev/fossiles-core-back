-- Auditoria: recepcion de distribucion y carga de inventario en kiosco
-- Ejecutar en PostgreSQL contra la BD de Fossiles.
--
-- Flujo esperado:
--   CONFIRMED -> sendShipment -> SENT (salida PT/Devoluciones)
--   confirmReceipt -> DELIVERED (entrada kiosco + kardex TRANSFER_IN)

-- =============================================================================
-- 1) Envios DELIVERED sin kardex TRANSFER_IN (posible inventario no registrado)
-- =============================================================================
SELECT
    ps.id AS shipment_id,
    ps.shipment_number,
    ps.status,
    l.name AS kiosk,
    psd.id AS detail_id,
    p.code AS product_code,
    psd.size_label,
    psd.quantity AS qty_sent,
    psd.quantity_received,
    EXISTS (
        SELECT 1 FROM product_inventory_kardex k
        WHERE k.reference_type = 'SHIPMENT'
          AND k.reference_id = ps.id
          AND k.movement_type = 'TRANSFER_IN'
          AND k.product_id = psd.product_id
          AND k.location_id = ps.location_id
          AND ((k.color_id IS NULL AND psd.color_id IS NULL) OR k.color_id = psd.color_id)
    ) AS has_transfer_in_kardex
FROM product_shipment ps
JOIN product_shipment_detail psd ON psd.shipment_id = ps.id
JOIN product p ON p.id = psd.product_id
LEFT JOIN locations l ON l.id = ps.location_id
WHERE ps.distribution_id IS NOT NULL
  AND ps.status = 'DELIVERED'
  AND ps.location_id IS NOT NULL
  AND upper(coalesce(p.code, '')) NOT LIKE 'SUM%'
ORDER BY ps.id, psd.id;

-- Solo anomalias:
-- SELECT * FROM (...) WHERE NOT has_transfer_in_kardex;

-- =============================================================================
-- 2) Envios en transito (SENT) pendientes de confirmar recepcion
-- =============================================================================
SELECT
    ps.id,
    ps.shipment_number,
    l.name AS kiosk,
    ps.sent_at,
    count(psd.id) AS lineas
FROM product_shipment ps
LEFT JOIN locations l ON l.id = ps.location_id
JOIN product_shipment_detail psd ON psd.shipment_id = ps.id
WHERE ps.distribution_id IS NOT NULL
  AND ps.status = 'SENT'
GROUP BY ps.id, ps.shipment_number, l.name, ps.sent_at
ORDER BY ps.sent_at;

-- =============================================================================
-- 3) Comparar stock kiosco vs cantidad recibida (ultimos 30 dias)
-- =============================================================================
SELECT
    ps.shipment_number,
    l.name AS kiosk,
    p.code,
    psd.size_label,
    psd.quantity_received,
    pil.quantity AS stock_kiosco_total,
    pil.sizes_data
FROM product_shipment ps
JOIN product_shipment_detail psd ON psd.shipment_id = ps.id
JOIN product p ON p.id = psd.product_id
LEFT JOIN locations l ON l.id = ps.location_id
LEFT JOIN product_inventory_location pil
  ON pil.product_id = psd.product_id
 AND pil.location_id = ps.location_id
 AND ((pil.color_id IS NULL AND psd.color_id IS NULL) OR pil.color_id = psd.color_id)
WHERE ps.status = 'DELIVERED'
  AND ps.distribution_id IS NOT NULL
  AND ps.received_at >= CURRENT_DATE - INTERVAL '30 days'
ORDER BY ps.received_at DESC
LIMIT 200;

-- =============================================================================
-- 4) Resumen por distribucion
-- =============================================================================
SELECT
    pd.distribution_number,
    count(*) FILTER (WHERE ps.status = 'SENT') AS envios_en_transito,
    count(*) FILTER (WHERE ps.status = 'DELIVERED') AS envios_entregados
FROM product_distribution pd
LEFT JOIN product_shipment ps ON ps.distribution_id = pd.id
GROUP BY pd.id, pd.distribution_number
HAVING count(*) FILTER (WHERE ps.status = 'SENT') > 0
    OR count(*) FILTER (WHERE ps.status = 'DELIVERED') > 0
ORDER BY pd.distribution_number DESC;
