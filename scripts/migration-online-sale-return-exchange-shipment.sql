-- Guarda el ENVL del envío de cambio asignado a cada CAMBIO de venta en línea.
-- Ejecutar manualmente en PostgreSQL ANTES del deploy del backend (ddl-auto=validate).

ALTER TABLE online_sale_return
    ADD COLUMN IF NOT EXISTS exchange_shipment_number VARCHAR(50);

COMMENT ON COLUMN online_sale_return.exchange_shipment_number IS
    'ENVL del envío de cambio (nuevo o existente) al que quedó asignado el cambio.';

-- Opcional: rellenar cambios anteriores que crearon su propio envío (misma fecha, ENVIO_REL = envío original).
UPDATE online_sale_return r
SET exchange_shipment_number = (
    SELECT s.shipment_number
    FROM online_sale s
    WHERE s.observations LIKE '%CAMBIO_Q0(ENVIO_REL=' || r.related_shipment_number || ')%'
      AND s.shipment_number IS NOT NULL
      AND s.shipment_number <> r.related_shipment_number
      AND s.created_at BETWEEN r.created_at - INTERVAL '10 minutes' AND r.created_at + INTERVAL '10 minutes'
    ORDER BY s.id
    LIMIT 1)
WHERE r.exchange_shipment_number IS NULL
  AND r.return_reason ILIKE 'CAMBIO%'
  AND r.related_shipment_number IS NOT NULL;
