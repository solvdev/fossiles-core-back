-- OPI / OPC (cinchos sin kiosko, Luis Felipe solo dirección): location_id opcional.
-- OBLIGATORIO en producción si generan envío desde OP sin kiosko.
-- Error sin esto: null value in column "location_id" violates not-null constraint
--
-- Ejecutar en PostgreSQL (fossilesgt):
--   psql ... -f migration-product-shipment-opi-null-location.sql

ALTER TABLE product_shipment
    ALTER COLUMN location_id DROP NOT NULL;

COMMENT ON COLUMN product_shipment.location_id IS 'Kiosko destino; NULL = envío OPI/OPC solo con dirección en notes (DESTINO:)';
