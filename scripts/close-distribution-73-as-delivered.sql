-- =============================================================================
-- Cerrar distribución 73 como ya enviada / recibida
-- =============================================================================
-- Efecto (solo estados, NO mueve inventario ni kardex):
--   product_shipment        -> DELIVERED (+ sent_at / received_at)
--   product_shipment_detail -> quantity_received = quantity (si está vacío)
--   product_distribution    -> COMPLETED
--   production_order        -> COMPLETED (OPs ligadas a la dist)
--   online_sale             -> ENVIADO (si hay ventas ligadas a esas OP)
--   partial_release         -> SHIPPED (si aplica)
--
-- Cambiar v_dist_id si el id no es 73.
-- Revisar la sección A (SELECT) antes de correr el UPDATE (sección B).
-- =============================================================================

BEGIN;

-- Parámetro
DO $$
BEGIN
  CREATE TEMP TABLE IF NOT EXISTS tmp_close_dist (dist_id BIGINT PRIMARY KEY);
  DELETE FROM tmp_close_dist;
  INSERT INTO tmp_close_dist (dist_id) VALUES (73);
END $$;

-- =============================================================================
-- A) PREVIA — estado actual
-- =============================================================================
SELECT
  pd.id,
  pd.distribution_number,
  pd.status,
  pd.distribution_date,
  (SELECT COUNT(*) FROM product_shipment ps WHERE ps.distribution_id = pd.id) AS envios,
  (SELECT COUNT(*) FROM product_shipment ps
    WHERE ps.distribution_id = pd.id
      AND UPPER(COALESCE(ps.status, '')) NOT IN ('DELIVERED', 'CANCELLED')) AS envios_abiertos
FROM product_distribution pd
WHERE pd.id IN (SELECT dist_id FROM tmp_close_dist);

SELECT
  ps.id,
  ps.shipment_number,
  ps.status,
  l.name AS kiosk,
  ps.sent_at,
  ps.received_at
FROM product_shipment ps
LEFT JOIN locations l ON l.id = ps.location_id
WHERE ps.distribution_id IN (SELECT dist_id FROM tmp_close_dist)
ORDER BY ps.id;

SELECT
  po.id,
  po.code,
  po.status,
  po.order_type
FROM production_order po
WHERE po.distribution_id IN (SELECT dist_id FROM tmp_close_dist)
ORDER BY po.id;

-- =============================================================================
-- B) CIERRE
-- =============================================================================

-- 1) Detalle: marcar recibido = enviado si falta
UPDATE product_shipment_detail psd
SET quantity_received = psd.quantity
FROM product_shipment ps
WHERE psd.shipment_id = ps.id
  AND ps.distribution_id IN (SELECT dist_id FROM tmp_close_dist)
  AND UPPER(COALESCE(ps.status, '')) <> 'CANCELLED'
  AND psd.quantity_received IS NULL;

-- 2) Envíos de la distribución -> DELIVERED (ya enviado + recibido)
UPDATE product_shipment ps
SET status = 'DELIVERED',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = COALESCE(ps.received_at, NOW()),
    updated_at = NOW()
WHERE ps.distribution_id IN (SELECT dist_id FROM tmp_close_dist)
  AND UPPER(COALESCE(ps.status, '')) NOT IN ('DELIVERED', 'CANCELLED');

-- 3) Distribución -> COMPLETED
UPDATE product_distribution pd
SET status = 'COMPLETED',
    updated_at = NOW()
WHERE pd.id IN (SELECT dist_id FROM tmp_close_dist)
  AND UPPER(COALESCE(pd.status, '')) <> 'COMPLETED';

-- 4) OPs ligadas a la distribución -> COMPLETED
UPDATE production_order po
SET status = 'COMPLETED',
    materials_consumed = TRUE,
    materials_consumed_at = COALESCE(po.materials_consumed_at, NOW()),
    warehouse_receipt_closed_at = COALESCE(po.warehouse_receipt_closed_at, NOW()),
    updated_at = NOW()
WHERE po.distribution_id IN (SELECT dist_id FROM tmp_close_dist)
  AND UPPER(COALESCE(po.status, '')) NOT IN ('COMPLETED', 'CANCELLED', 'DRAFT');

-- 5) Ventas en línea ligadas a esas OP -> ENVIADO
UPDATE online_sale os
SET status = 'ENVIADO',
    updated_at = NOW()
WHERE os.status NOT IN ('ENVIADO', 'ENTREGADO', 'CANCELADO', 'DEVOLUCION', 'ANULADA')
  AND (
    os.production_order_id IN (
      SELECT id FROM production_order
      WHERE distribution_id IN (SELECT dist_id FROM tmp_close_dist)
    )
    OR os.id IN (
      SELECT poi.online_sale_id
      FROM production_order_item poi
      WHERE poi.online_sale_id IS NOT NULL
        AND poi.production_order_id IN (
          SELECT id FROM production_order
          WHERE distribution_id IN (SELECT dist_id FROM tmp_close_dist)
        )
    )
  );

-- 6) Liberaciones parciales de esas OP -> SHIPPED
UPDATE production_order_partial_release r
SET status = 'SHIPPED',
    updated_at = NOW()
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND r.production_order_id IN (
    SELECT id FROM production_order
    WHERE distribution_id IN (SELECT dist_id FROM tmp_close_dist)
  );

-- =============================================================================
-- C) VERIFICACIÓN
-- =============================================================================
SELECT
  pd.id,
  pd.distribution_number,
  pd.status AS dist_status,
  COUNT(ps.id) AS envios,
  COUNT(*) FILTER (WHERE UPPER(COALESCE(ps.status, '')) = 'DELIVERED') AS entregados,
  COUNT(*) FILTER (WHERE UPPER(COALESCE(ps.status, '')) NOT IN ('DELIVERED', 'CANCELLED')) AS abiertos
FROM product_distribution pd
LEFT JOIN product_shipment ps ON ps.distribution_id = pd.id
WHERE pd.id IN (SELECT dist_id FROM tmp_close_dist)
GROUP BY pd.id, pd.distribution_number, pd.status;

SELECT po.id, po.code, po.status
FROM production_order po
WHERE po.distribution_id IN (SELECT dist_id FROM tmp_close_dist);

-- Si todo OK:
COMMIT;
-- Si algo salió mal:
-- ROLLBACK;
