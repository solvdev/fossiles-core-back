-- Notas por línea al confirmar recepción de envío en kiosko

ALTER TABLE product_shipment_detail
    ADD COLUMN IF NOT EXISTS received_line_notes VARCHAR(500);
