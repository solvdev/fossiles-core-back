-- Consulta «Envíos» (GET /api/product-distributions/shipments/search): índices por fecha de creación, envío y
-- recepción. OPCIONAL: el endpoint funciona sin ellos; ayudan cuando la tabla product_shipment crezca.
-- Idempotente. CONCURRENTLY no bloquea escrituras, pero no puede ir dentro de una transacción: correr cada
-- sentencia suelta (psql sin BEGIN).

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_shipment_created_at ON product_shipment (created_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_shipment_sent_at ON product_shipment (sent_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_shipment_received_at ON product_shipment (received_at);

-- Verificar
SELECT indexname FROM pg_indexes
WHERE tablename = 'product_shipment'
  AND indexname IN ('idx_product_shipment_created_at', 'idx_product_shipment_sent_at', 'idx_product_shipment_received_at');
