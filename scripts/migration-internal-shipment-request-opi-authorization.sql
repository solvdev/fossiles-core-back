-- Autorización de producción OPI vinculada a solicitudes de envío interno
ALTER TABLE internal_shipment_request
  ADD COLUMN IF NOT EXISTS opi_authorized_by BIGINT,
  ADD COLUMN IF NOT EXISTS opi_authorized_at TIMESTAMP;
