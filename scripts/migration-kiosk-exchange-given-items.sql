-- Líneas de productos entregados en boletas de cambio (1 devolución → N entregas).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

CREATE TABLE IF NOT EXISTS kiosk_exchange_slip_given_item (
    id BIGSERIAL PRIMARY KEY,
    exchange_slip_id BIGINT NOT NULL REFERENCES kiosk_exchange_slip(id) ON DELETE CASCADE,
    line_no INTEGER NOT NULL,
    product_id BIGINT NOT NULL,
    color_id BIGINT,
    size VARCHAR(20),
    hardware_condition VARCHAR(20),
    quantity NUMERIC(12, 3) NOT NULL,
    unit_price NUMERIC(12, 2),
    line_total NUMERIC(12, 2),
    given_movement_id BIGINT,
    CONSTRAINT uq_kiosk_exchange_slip_given_line UNIQUE (exchange_slip_id, line_no)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_given_item_slip
    ON kiosk_exchange_slip_given_item (exchange_slip_id);

COMMENT ON TABLE kiosk_exchange_slip_given_item IS
    'Productos entregados en una boleta de cambio (soporta 1→N).';
COMMENT ON COLUMN kiosk_exchange_slip_given_item.line_no IS
    'Orden de la línea (1-based).';
COMMENT ON COLUMN kiosk_exchange_slip_given_item.given_movement_id IS
    'Movimiento CAMBIO (−) asociado a esta línea.';
