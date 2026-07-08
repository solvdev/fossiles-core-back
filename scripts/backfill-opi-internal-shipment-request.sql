-- Backfill: OPs INTERNA existentes en PENDING/IN_PROGRESS sin solicitud vinculada.
-- Crea solicitud OPI ya autorizada para no bloquear producción en curso.

INSERT INTO internal_shipment_request (
    status,
    request_type,
    recipient_name,
    notes,
    document_date,
    production_order_id,
    requested_by,
    requested_at,
    reviewed_by,
    reviewed_at,
    opi_authorized_by,
    opi_authorized_at,
    created_at,
    updated_at
)
SELECT
    'APROBADA',
    'OPI',
    COALESCE(NULLIF(TRIM(po.customer_name), ''), 'Producción interna'),
    COALESCE(po.observations, 'Migración: OPI existente antes de autorización obligatoria.'),
    po.start_date::text,
    po.id,
    po.created_by,
    COALESCE(po.created_at, NOW()),
    po.created_by,
    COALESCE(po.created_at, NOW()),
    po.created_by,
    COALESCE(po.created_at, NOW()),
    NOW(),
    NOW()
FROM production_order po
WHERE UPPER(COALESCE(po.order_type, '')) = 'INTERNA'
  AND UPPER(COALESCE(po.status, '')) IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')
  AND NOT EXISTS (
      SELECT 1 FROM internal_shipment_request r
      WHERE r.production_order_id = po.id
  );

-- Líneas de solicitud desde ítems de OP (cantidad simple, sin tallas expandidas).
INSERT INTO internal_shipment_request_line (
    request_id,
    line_order,
    product_id,
    color_id,
    size,
    quantity
)
SELECT
    r.id,
    ROW_NUMBER() OVER (PARTITION BY r.id ORDER BY poi.id),
    poi.product_id,
    poi.color_id,
    NULL,
    COALESCE(poi.quantity, 0)
FROM internal_shipment_request r
JOIN production_order po ON po.id = r.production_order_id
JOIN production_order_item poi ON poi.production_order_id = po.id
WHERE r.request_type = 'OPI'
  AND r.notes LIKE '%Migración: OPI existente%'
  AND COALESCE(poi.quantity, 0) > 0
  AND NOT EXISTS (
      SELECT 1 FROM internal_shipment_request_line l WHERE l.request_id = r.id
  );
