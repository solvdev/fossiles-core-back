-- Descuento manual en solicitudes DEFECTOS (porcentaje o monto unitario)
ALTER TABLE internal_shipment_request
    ADD COLUMN IF NOT EXISTS discount_percent NUMERIC(5, 2);

ALTER TABLE internal_shipment_request
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(12, 2);

COMMENT ON COLUMN internal_shipment_request.discount_percent IS 'DEFECTOS: porcentaje del precio catálogo (ej. 50 = mitad). PLANILLA usa 50 fijo.';
COMMENT ON COLUMN internal_shipment_request.discount_amount IS 'DEFECTOS: precio unitario fijo Q (alternativa al porcentaje).';
