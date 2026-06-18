-- Precio unitario por línea de OP (OPV / MARCAS — precios especiales por cliente)
ALTER TABLE production_order_item
    ADD COLUMN IF NOT EXISTS unit_price DECIMAL(12, 2) NULL;

COMMENT ON COLUMN production_order_item.unit_price IS 'Precio unitario acordado para la línea (OPV/MARCAS); si NULL se usa catálogo al imprimir';
