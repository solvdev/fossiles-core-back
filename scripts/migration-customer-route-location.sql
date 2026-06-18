-- Ruta / ubicación de entrega LF en cliente (código R0101, R0203, …)
-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate).

ALTER TABLE customer ADD COLUMN IF NOT EXISTS route_location_code VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_customer_route_location ON customer (route_location_code);

COMMENT ON COLUMN customer.route_location_code IS 'Código ruta LF: R{NN}0{NN} (ej. R0101 Zacapa)';
