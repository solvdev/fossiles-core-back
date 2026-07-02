-- Vincula solicitud ENVI interna con OPI generada por faltante de stock PT/Devoluciones
-- PostgreSQL

ALTER TABLE internal_shipment_request
    ADD COLUMN IF NOT EXISTS production_order_id BIGINT REFERENCES production_order (id);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_production_order_id
    ON internal_shipment_request (production_order_id);

COMMENT ON COLUMN internal_shipment_request.production_order_id IS
    'OPI (INTERNA) generada automáticamente cuando no hay stock PT/Devoluciones al crear la solicitud';
