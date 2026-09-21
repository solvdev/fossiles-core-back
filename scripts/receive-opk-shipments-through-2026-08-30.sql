-- =====================================================================
-- Recibir distribución: envíos EN CAMINO (SENT) hasta 2026-08-30
-- =====================================================================
-- Lo que ves en POS → Recibir distribución (ej. COATEPEQ-ENV-00039).
-- No filtra por código OPK: el número del envío es {kiosko}-ENV-#####.
--
-- Corte: COALESCE(sent_at, updated_at, created_at)::date <= 2026-08-30
--   - 13/08/2026 (ENV-00039) → se recibe
--   - 02/09/2026 (ENV-00040) → se deja pendiente
--
-- Qué hace:
--   1) SENT -> DELIVERED (sale de Recibir distribución)
--   2) quantity_received = quantity
--   3) Liberaciones parciales ligadas -> SHIPPED
--
-- Qué NO hace: kardex TRANSFER_IN ni stock de kiosco.
-- Idempotente. Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    'envios_en_camino_a_recibir' AS concepto,
    COUNT(*) AS cantidad
FROM product_shipment ps
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
  AND ps.location_id IS NOT NULL
  AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30'
UNION ALL
SELECT 'lineas', COUNT(*)
FROM product_shipment_detail d
JOIN product_shipment ps ON ps.id = d.shipment_id
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
  AND ps.location_id IS NOT NULL
  AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30';

SELECT
    ps.id,
    ps.shipment_number,
    po.code AS op,
    l.code AS kiosko,
    l.name AS kiosko_nombre,
    COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date AS fecha_envio,
    ps.sent_at
FROM product_shipment ps
LEFT JOIN production_order po ON po.id = COALESCE(
    ps.production_order_id,
    (SELECT r.production_order_id FROM production_order_partial_release r WHERE r.id = ps.partial_release_id)
)
LEFT JOIN location l ON l.id = ps.location_id
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
  AND ps.location_id IS NOT NULL
  AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30'
ORDER BY COALESCE(ps.sent_at, ps.updated_at, ps.created_at) DESC, ps.id DESC;

-- =====================================================================
-- B) UPDATES
-- =====================================================================

UPDATE product_shipment_detail d
SET quantity_received = COALESCE(d.quantity, 0),
    quantity_difference = 0,
    updated_at = NOW()
WHERE d.shipment_id IN (
    SELECT ps.id
    FROM product_shipment ps
    WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
      AND ps.location_id IS NOT NULL
      AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30'
);

UPDATE product_shipment ps
SET status = 'DELIVERED',
    received_at = COALESCE(ps.received_at, NOW()),
    received_notes = COALESCE(
        NULLIF(TRIM(ps.received_notes), ''),
        'Cierre administrativo: recepción envíos en camino hasta 2026-08-30'
    ),
    updated_at = NOW()
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
  AND ps.location_id IS NOT NULL
  AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30';

UPDATE production_order_partial_release r
SET status = 'SHIPPED'
WHERE r.status NOT IN ('SHIPPED', 'CANCELLED')
  AND EXISTS (
      SELECT 1
      FROM product_shipment ps
      WHERE ps.partial_release_id = r.id
        AND UPPER(TRIM(COALESCE(ps.status, ''))) = 'DELIVERED'
        AND ps.location_id IS NOT NULL
        AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30'
  );

-- =====================================================================
-- C) VERIFICACIÓN — debe quedar 0
-- =====================================================================
SELECT 'envios_SENT_restantes_corte' AS check_name, COUNT(*) AS restantes
FROM product_shipment ps
WHERE UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
  AND ps.location_id IS NOT NULL
  AND COALESCE(ps.sent_at, ps.updated_at, ps.created_at)::date <= DATE '2026-08-30';
