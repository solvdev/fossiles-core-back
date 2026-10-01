-- Líneas de productos que ingresan en boletas de cambio (N devoluciones → M entregas).
-- Ejecutar manualmente en PostgreSQL ANTES del deploy (ddl-auto=validate).
-- Boletas anteriores no tienen filas aquí: se leen de kiosk_exchange_slip.returned_* (una sola línea).

CREATE TABLE IF NOT EXISTS kiosk_exchange_slip_returned_item (
    id BIGSERIAL PRIMARY KEY,
    exchange_slip_id BIGINT NOT NULL REFERENCES kiosk_exchange_slip(id) ON DELETE CASCADE,
    line_no INTEGER NOT NULL,
    original_sale_item_id BIGINT,
    product_id BIGINT NOT NULL,
    color_id BIGINT,
    size VARCHAR(20),
    quantity NUMERIC(12, 3) NOT NULL,
    unit_price NUMERIC(12, 2),
    line_total NUMERIC(12, 2),
    return_movement_id BIGINT,
    CONSTRAINT uq_kiosk_exchange_slip_returned_line UNIQUE (exchange_slip_id, line_no)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_returned_item_slip
    ON kiosk_exchange_slip_returned_item (exchange_slip_id);

COMMENT ON TABLE kiosk_exchange_slip_returned_item IS
    'Productos devueltos (ingreso) en una boleta de cambio; soporta varias líneas de la factura original.';
COMMENT ON COLUMN kiosk_exchange_slip_returned_item.line_no IS
    'Orden de la línea (1-based).';
COMMENT ON COLUMN kiosk_exchange_slip_returned_item.original_sale_item_id IS
    'Línea de la factura original (kiosk_sale_item.id); null en cambio libre.';
COMMENT ON COLUMN kiosk_exchange_slip_returned_item.return_movement_id IS
    'Movimiento CAMBIO (+) asociado a esta línea.';
