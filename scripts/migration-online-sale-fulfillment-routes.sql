-- Venta mixta sin subpedidos: ruta por línea en venta online y vínculo OP → línea venta.
-- Ejecutar una vez por entorno MySQL antes/después del despliegue (ajustar motor si usa otro RDBMS).

ALTER TABLE online_sale_item
    ADD COLUMN fulfillment_route VARCHAR(20) NULL COMMENT 'DISPATCH | PRODUCE';

ALTER TABLE production_order_item
    ADD COLUMN online_sale_item_id BIGINT NULL;

CREATE INDEX idx_production_order_item_online_sale_item_id
    ON production_order_item (online_sale_item_id);
