-- Auditoría: salidas de Bodega PT / Devoluciones que NO se registraron
-- Complementa scripts/audit-bodega-pt-production-inventory.sql (que audita entradas).
-- PostgreSQL. Cada bloque cuantifica un hallazgo del informe de auditoría.
--
-- Bodegas de despacho (ProductInventoryService.getDispatchSourceWarehouses):
--   BODEGA_PT + la bodega de devoluciones (OnlineSaleReturnsWarehouseLocator).

-- =============================================================================
-- 0) Vista auxiliar: ubicaciones de despacho
-- =============================================================================
-- SELECT id, code, name FROM locations
--  WHERE upper(code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
--                        'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN');

-- =============================================================================
-- 1) HALLAZGO A — Envíos a kiosko que entraron al destino sin descargar PT
--    sendShipment() retorna antes del decremento cuando location_id != null.
--    Esperado si el sistema fuera consistente: unidades_descargadas = unidades_documento.
-- =============================================================================
WITH dispatch_wh AS (
    SELECT id FROM locations
    WHERE upper(code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
                          'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN')
),
doc AS (
    SELECT s.id AS shipment_id,
           s.shipment_number,
           s.status,
           s.sent_at,
           l.name AS kiosko_destino,
           sum(coalesce(d.quantity, 0)) AS unidades_documento
    FROM product_shipment s
    JOIN locations l ON l.id = s.location_id
    JOIN product_shipment_detail d ON d.shipment_id = s.id
    WHERE s.location_id IS NOT NULL
      AND upper(coalesce(s.status, '')) IN ('SENT', 'DELIVERED')
    GROUP BY s.id, s.shipment_number, s.status, s.sent_at, l.name
),
salidas AS (
    SELECT k.reference_id AS shipment_id,
           sum(-k.quantity) AS unidades_descargadas
    FROM product_inventory_kardex k
    WHERE k.reference_type = 'SHIPMENT'
      AND k.quantity < 0
      AND k.location_id IN (SELECT id FROM dispatch_wh)
    GROUP BY k.reference_id
)
SELECT doc.*,
       coalesce(salidas.unidades_descargadas, 0) AS unidades_descargadas_pt,
       doc.unidades_documento - coalesce(salidas.unidades_descargadas, 0) AS unidades_no_descargadas
FROM doc
LEFT JOIN salidas ON salidas.shipment_id = doc.shipment_id
WHERE doc.unidades_documento > coalesce(salidas.unidades_descargadas, 0)
ORDER BY doc.sent_at DESC NULLS LAST;

-- =============================================================================
-- 1b) Impacto total del hallazgo A (unidades infladas por kiosko)
-- =============================================================================
WITH dispatch_wh AS (
    SELECT id FROM locations
    WHERE upper(code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
                          'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN')
),
entradas_kiosko AS (
    SELECT k.product_id, k.color_id, sum(k.quantity) AS qty_in
    FROM product_inventory_kardex k
    JOIN locations l ON l.id = k.location_id
    WHERE k.movement_type = 'TRANSFER_IN'
      AND k.reference_type = 'SHIPMENT'
      AND upper(coalesce(l.categoria, '')) LIKE '%KIOSK%'
    GROUP BY k.product_id, k.color_id
),
salidas_pt AS (
    SELECT k.product_id, k.color_id, sum(-k.quantity) AS qty_out
    FROM product_inventory_kardex k
    WHERE k.reference_type = 'SHIPMENT'
      AND k.quantity < 0
      AND k.location_id IN (SELECT id FROM dispatch_wh)
    GROUP BY k.product_id, k.color_id
)
SELECT p.code AS product_code,
       p.name AS product_name,
       c.name AS color_name,
       e.qty_in AS entradas_kiosko,
       coalesce(s.qty_out, 0) AS salidas_pt,
       e.qty_in - coalesce(s.qty_out, 0) AS unidades_duplicadas
FROM entradas_kiosko e
JOIN product p ON p.id = e.product_id
LEFT JOIN colors c ON c.id = e.color_id
LEFT JOIN salidas_pt s ON s.product_id = e.product_id
    AND ((s.color_id IS NULL AND e.color_id IS NULL) OR s.color_id = e.color_id)
WHERE e.qty_in - coalesce(s.qty_out, 0) > 0
ORDER BY (e.qty_in - coalesce(s.qty_out, 0)) DESC;

-- =============================================================================
-- 2) HALLAZGO B — Colisión de idempotencia por talla en ENVÍOS
--    El guard de kardex es (reference_type, reference_id, movement_type,
--    product_id, location_id, color_id): NO incluye size_label.
--    Un envío con 2+ tallas del mismo producto+color descarga solo la primera.
-- =============================================================================
WITH dispatch_wh AS (
    SELECT id FROM locations
    WHERE upper(code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
                          'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN')
),
lineas AS (
    SELECT d.shipment_id,
           d.product_id,
           d.color_id,
           count(*) AS lineas_por_variante,
           sum(coalesce(d.quantity, 0)) AS qty_documento,
           string_agg(coalesce(d.size_label, '(sin talla)') || ':' || d.quantity, ', '
                      ORDER BY d.size_label) AS desglose
    FROM product_shipment_detail d
    JOIN product_shipment s ON s.id = d.shipment_id
    WHERE upper(coalesce(s.status, '')) IN ('SENT', 'DELIVERED')
    GROUP BY d.shipment_id, d.product_id, d.color_id
    HAVING count(*) > 1
)
SELECT s.shipment_number,
       s.status,
       s.sent_at,
       p.code AS product_code,
       c.name AS color_name,
       li.lineas_por_variante,
       li.desglose,
       li.qty_documento,
       coalesce((SELECT sum(-k.quantity)
                 FROM product_inventory_kardex k
                 WHERE k.reference_type = 'SHIPMENT'
                   AND k.reference_id = li.shipment_id
                   AND k.product_id = li.product_id
                   AND ((k.color_id IS NULL AND li.color_id IS NULL) OR k.color_id = li.color_id)
                   AND k.quantity < 0
                   AND k.location_id IN (SELECT id FROM dispatch_wh)), 0) AS qty_descargada
FROM lineas li
JOIN product_shipment s ON s.id = li.shipment_id
JOIN product p ON p.id = li.product_id
LEFT JOIN colors c ON c.id = li.color_id
ORDER BY s.sent_at DESC NULLS LAST;

-- =============================================================================
-- 3) HALLAZGO B — Misma colisión en VENTA EN LÍNEA (ONLINE_SALE_PREPARE)
--    Ventas con varias líneas del mismo producto+color (tallas distintas):
--    solo la primera genera movimiento.
-- =============================================================================
WITH dispatch_wh AS (
    SELECT id FROM locations
    WHERE upper(code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
                          'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN')
),
lineas AS (
    SELECT i.online_sale_id,
           i.product_id,
           i.color_id,
           count(*) AS lineas,
           sum(coalesce(i.quantity, 0)) AS qty_vendida,
           string_agg(coalesce(i.size, '(sin talla)') || ':' || i.quantity, ', ') AS desglose
    FROM online_sale_item i
    GROUP BY i.online_sale_id, i.product_id, i.color_id
    HAVING count(*) > 1
)
SELECT os.sale_number,
       os.status,
       p.code AS product_code,
       c.name AS color_name,
       li.lineas,
       li.desglose,
       li.qty_vendida,
       coalesce((SELECT sum(-k.quantity)
                 FROM product_inventory_kardex k
                 WHERE k.reference_type = 'ONLINE_SALE_PREPARE'
                   AND k.reference_id = li.online_sale_id
                   AND k.product_id = li.product_id
                   AND ((k.color_id IS NULL AND li.color_id IS NULL) OR k.color_id = li.color_id)
                   AND k.quantity < 0
                   AND k.location_id IN (SELECT id FROM dispatch_wh)), 0) AS qty_descargada
FROM lineas li
JOIN online_sale os ON os.id = li.online_sale_id
JOIN product p ON p.id = li.product_id
LEFT JOIN colors c ON c.id = li.color_id
WHERE upper(coalesce(os.status, '')) IN ('PRODUCIDO', 'ENVIADO', 'ENTREGADO')
ORDER BY os.id DESC;

-- =============================================================================
-- 4) HALLAZGO C — Reversión de envíos SENT sin contrapartida en kardex
--    reverseSentShipmentDispatchInventory() usa incrementInventory(), que NO
--    escribe kardex y NO borra la fila negativa original: el stock sube pero el
--    kardex sigue mostrando la salida, y una segunda edición vuelve a acreditar.
-- =============================================================================
SELECT s.shipment_number,
       s.status,
       count(*) FILTER (WHERE k.movement_type = 'SHIPMENT') AS filas_shipment,
       count(*) FILTER (WHERE k.movement_type = 'SHIPMENT_REDISPATCH') AS filas_redispatch,
       sum(-k.quantity) FILTER (WHERE k.movement_type = 'SHIPMENT') AS qty_salida_original,
       sum(-k.quantity) FILTER (WHERE k.movement_type = 'SHIPMENT_REDISPATCH') AS qty_salida_reenvio
FROM product_inventory_kardex k
JOIN product_shipment s ON s.id = k.reference_id
WHERE k.reference_type = 'SHIPMENT'
  AND k.movement_type IN ('SHIPMENT', 'SHIPMENT_REDISPATCH')
  AND k.quantity < 0
GROUP BY s.id, s.shipment_number, s.status
HAVING count(*) FILTER (WHERE k.movement_type = 'SHIPMENT_REDISPATCH') > 0
ORDER BY s.id DESC;

-- =============================================================================
-- 5) Descuadre global por ubicación: stock actual vs neto de kardex
--    (versión por ubicación de la consulta 4 del script de entradas)
-- =============================================================================
WITH kardex_net AS (
    SELECT k.location_id, k.product_id, k.color_id, sum(k.quantity) AS neto
    FROM product_inventory_kardex k
    GROUP BY k.location_id, k.product_id, k.color_id
)
SELECT l.code AS location_code,
       l.name AS location_name,
       p.code AS product_code,
       c.name AS color_name,
       pil.quantity AS stock_actual,
       coalesce(kn.neto, 0) AS neto_kardex,
       pil.quantity - coalesce(kn.neto, 0) AS diff
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
LEFT JOIN kardex_net kn ON kn.location_id = pil.location_id
    AND kn.product_id = pil.product_id
    AND ((kn.color_id IS NULL AND pil.color_id IS NULL) OR kn.color_id = pil.color_id)
WHERE abs(coalesce(pil.quantity, 0) - coalesce(kn.neto, 0)) > 0.001
ORDER BY abs(pil.quantity - coalesce(kn.neto, 0)) DESC
LIMIT 200;

-- =============================================================================
-- 6) Cinchos FOSS: sizes_data desalineado con quantity en bodegas de despacho
--    (síntoma de decrementos aplicados al total sin talla, o al revés)
-- =============================================================================
SELECT l.code AS location_code,
       p.code AS product_code,
       c.name AS color_name,
       pil.quantity,
       pil.sizes_data,
       (SELECT coalesce(sum(value::numeric), 0)
        FROM jsonb_each_text(pil.sizes_data::jsonb)) AS suma_tallas
FROM product_inventory_location pil
JOIN locations l ON l.id = pil.location_id
JOIN product p ON p.id = pil.product_id
LEFT JOIN colors c ON c.id = pil.color_id
WHERE pil.sizes_data IS NOT NULL
  AND pil.sizes_data <> ''
  AND pil.sizes_data <> '{}'
  AND upper(l.code) IN ('BODEGA_PT','BODEGA_DEVOLUCIONES','BODEGA_DEV','DEVOLUCION',
                        'DEVOLUCIONES','BODEGA_RET','BODEGA_RETURN')
  AND abs(coalesce(pil.quantity, 0) - (SELECT coalesce(sum(value::numeric), 0)
                                       FROM jsonb_each_text(pil.sizes_data::jsonb))) > 0.001
ORDER BY p.code;
