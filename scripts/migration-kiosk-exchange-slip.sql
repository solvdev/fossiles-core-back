-- Boletas de cambio y devoluciones en kiosko
-- Ejecutar manualmente en PostgreSQL si no usa ddl-auto=update

CREATE TABLE IF NOT EXISTS kiosk_exchange_slip_sequence (
    kiosk_location_id BIGINT NOT NULL,
    sequence_year INTEGER NOT NULL,
    last_number INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (kiosk_location_id, sequence_year)
);

CREATE TABLE IF NOT EXISTS kiosk_exchange_slip (
    id BIGSERIAL PRIMARY KEY,
    slip_number VARCHAR(60) NOT NULL UNIQUE,
    slip_type VARCHAR(20) NOT NULL DEFAULT 'EXCHANGE',
    kiosk_location_id BIGINT NOT NULL,
    original_sale_id BIGINT NOT NULL,
    original_sale_item_id BIGINT NOT NULL,
    returned_product_id BIGINT NOT NULL,
    returned_color_id BIGINT,
    returned_size VARCHAR(20),
    returned_quantity NUMERIC(12, 3) NOT NULL,
    returned_amount NUMERIC(12, 2) NOT NULL,
    given_product_id BIGINT,
    given_color_id BIGINT,
    given_size VARCHAR(20),
    given_quantity NUMERIC(12, 3),
    given_amount NUMERIC(12, 2),
    difference_amount NUMERIC(12, 2),
    new_sale_id BIGINT,
    return_movement_id BIGINT,
    reintegro_movement_id BIGINT,
    apto BOOLEAN,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    reason VARCHAR(500),
    observations VARCHAR(1500),
    created_by BIGINT,
    created_at TIMESTAMP,
    completed_at TIMESTAMP,
    reintegrated_at TIMESTAMP,
    reintegrated_by BIGINT
);

CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_kiosk ON kiosk_exchange_slip (kiosk_location_id);
CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_status ON kiosk_exchange_slip (status);
CREATE INDEX IF NOT EXISTS idx_kiosk_exchange_slip_original_sale ON kiosk_exchange_slip (original_sale_id);
