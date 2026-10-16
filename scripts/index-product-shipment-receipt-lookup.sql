-- Recibir distribución (POS kiosko): consultas por kiosko + estado en product_shipment.
-- Hibernate filtra con upper(status) (findByStatusIgnoreCaseAndLocationId...), por eso el índice es funcional.
-- El prefijo location_id también sirve a las consultas que filtran solo por kiosko (p. ej. pendientes ya entregados).
-- Seguro de correr varias veces. CONCURRENTLY no corre dentro de una transacción: ejecutar tal cual (autocommit).

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_shipment_location_status_upper
    ON product_shipment (location_id, UPPER(status));

-- product_shipment_detail ya tiene el índice único (shipment_id, product_id, color_id, size_label,
-- hardware_condition) que cubre findByShipmentId / findByShipmentIdIn; no hace falta otro.
