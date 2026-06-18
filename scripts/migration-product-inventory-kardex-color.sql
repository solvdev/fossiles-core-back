-- Kardex de productos: registrar color por movimiento (variantes).
-- Ejecutar en PostgreSQL antes de desplegar el cambio de backend asociado.

ALTER TABLE product_inventory_kardex
    ADD COLUMN color_id BIGINT NULL;

ALTER TABLE product_inventory_kardex
    ADD CONSTRAINT fk_product_inventory_kardex_color
    FOREIGN KEY (color_id) REFERENCES colors (id);
