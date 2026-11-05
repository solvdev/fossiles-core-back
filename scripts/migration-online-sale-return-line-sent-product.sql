-- Guarda, en cada línea de un CAMBIO de venta en línea, qué artículo se envió a cambio
-- (el artículo de la línea es lo que regresó a Devoluciones).
-- Ejecutar manualmente en PostgreSQL ANTES del deploy del backend (ddl-auto=validate).
-- Idempotente. Los cambios anteriores quedan con estas columnas en NULL.

ALTER TABLE online_sale_return_line
    ADD COLUMN IF NOT EXISTS sent_product_id   BIGINT,
    ADD COLUMN IF NOT EXISTS sent_product_code VARCHAR(30),
    ADD COLUMN IF NOT EXISTS sent_product_name VARCHAR(150),
    ADD COLUMN IF NOT EXISTS sent_color_id     BIGINT,
    ADD COLUMN IF NOT EXISTS sent_color_name   VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sent_size         VARCHAR(50);

COMMENT ON COLUMN online_sale_return_line.sent_product_id IS
    'Artículo que se envió a cambio de esta línea devuelta (NULL en cambios anteriores o devoluciones).';
