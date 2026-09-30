-- =====================================================================
-- Distribución 76 → DELIVERED → SENT (vuelven a POS recepción)
-- =====================================================================
-- POS → Recibir distribución solo lista:
--   status = 'SENT' AND location_id IS NOT NULL
--
-- Qué hace:
--   1) Envios DELIVERED de dist 76 (con kiosko) → SENT
--   2) Limpia received_at / received_by / received_notes
--   3) Resetea quantity_received / quantity_difference en detalle
--   4) Si la distribución quedó COMPLETED, la deja en SENT
--
-- Qué NO hace:
--   - No toca kardex ni stock de kiosko
--   - Al volver a confirmar recepción en POS, el backend suele
--     saltar líneas ya aplicadas (hasShipmentReceiptLineApplied);
--     si el DELIVERED fue solo administrativo (sin stock), la
--     recepción en POS cargará inventario correctamente.
--
-- Flujo: A) PREVIA → B) UPDATES → C) VERIFICACIÓN
-- =====================================================================

-- =====================================================================
-- A) PREVIA — solo lectura
-- =====================================================================
SELECT
    ps.status,
    COUNT(*) AS cantidad
FROM product_shipment ps
WHERE ps.distribution_id = 76
GROUP BY ps.status
ORDER BY ps.status;

SELECT
    pd.id AS distribution_id,
    pd.distribution_number,
    pd.status AS distribution_status
FROM product_distribution pd
WHERE pd.id = 76;

SELECT
    ps.id,
    ps.shipment_number,
    ps.status,
    l.code AS kiosko,
    ps.sent_at,
    ps.received_at,
    CASE
        WHEN ps.location_id IS NULL THEN 'SIN_KIOSKO'
        WHEN UPPER(TRIM(COALESCE(ps.status, ''))) = 'DELIVERED'
            THEN 'SE_REABRE_A_SENT'
        WHEN UPPER(TRIM(COALESCE(ps.status, ''))) = 'SENT'
            THEN 'YA_EN_SENT'
        ELSE 'NO_TOCAR'
    END AS accion
FROM product_shipment ps
LEFT JOIN location l ON l.id = ps.location_id
WHERE ps.distribution_id = 76
ORDER BY ps.id;

-- =====================================================================
-- B) UPDATES
-- =====================================================================

UPDATE product_shipment_detail d
SET quantity_received = NULL,
    quantity_difference = NULL,
    received_line_notes = NULL,
    updated_at = NOW()
WHERE d.shipment_id IN (
    SELECT ps.id
    FROM product_shipment ps
    WHERE ps.distribution_id = 76
      AND ps.location_id IS NOT NULL
      AND UPPER(TRIM(COALESCE(ps.status, ''))) = 'DELIVERED'
);

UPDATE product_shipment ps
SET status = 'SENT',
    sent_at = COALESCE(ps.sent_at, NOW()),
    received_at = NULL,
    received_by = NULL,
    received_notes = NULL,
    updated_at = NOW()
WHERE ps.distribution_id = 76
  AND ps.location_id IS NOT NULL
  AND UPPER(TRIM(COALESCE(ps.status, ''))) = 'DELIVERED';

UPDATE product_distribution pd
SET status = 'SENT',
    updated_at = NOW()
WHERE pd.id = 76
  AND UPPER(TRIM(COALESCE(pd.status, ''))) = 'COMPLETED';

-- =====================================================================
-- C) VERIFICACIÓN
-- =====================================================================
SELECT
    ps.status,
    COUNT(*) AS cantidad
FROM product_shipment ps
WHERE ps.distribution_id = 76
GROUP BY ps.status
ORDER BY ps.status;

SELECT
    pd.id,
    pd.distribution_number,
    pd.status
FROM product_distribution pd
WHERE pd.id = 76;

SELECT
    ps.id,
    ps.shipment_number,
    ps.status,
    l.code AS kiosko,
    ps.sent_at,
    ps.received_at
FROM product_shipment ps
LEFT JOIN location l ON l.id = ps.location_id
WHERE ps.distribution_id = 76
ORDER BY ps.id;
