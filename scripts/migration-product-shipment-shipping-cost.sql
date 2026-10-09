-- Costo de envío por documento (parciales / OPC / OPV).
-- Antes solo vivía en observations de la OP (__OPV_SHIPPING__), y los parciales seq>1 salían en Q0.
ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS shipping_cost NUMERIC(12, 2);

COMMENT ON COLUMN product_shipment.shipping_cost IS
    'Costo de envío del documento; se imprime y se usa en CxC. Null = heredar regla de OP/parcial 1.';
