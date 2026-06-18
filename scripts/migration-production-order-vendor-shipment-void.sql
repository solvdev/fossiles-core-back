-- Anulación de documento de envío OPV/OPI sin fila product_shipment
ALTER TABLE production_order
    ADD COLUMN IF NOT EXISTS vendor_shipment_voided_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS vendor_shipment_voided_by BIGINT;

COMMENT ON COLUMN production_order.vendor_shipment_voided_at IS 'Marca documento de envío vendedor/interno como anulado (impresión sin product_shipment)';
COMMENT ON COLUMN production_order.vendor_shipment_voided_by IS 'Usuario que anuló el documento de envío';
