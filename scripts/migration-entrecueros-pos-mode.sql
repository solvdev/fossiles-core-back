-- Modalidad POS Entrecueros: flag de ubicación, productos elegibles y precios por cantidad.

ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS pos_mode VARCHAR(30) NOT NULL DEFAULT 'STANDARD';

COMMENT ON COLUMN locations.pos_mode IS
    'STANDARD = POS kiosco normal. ENTRECUEROS = catálogo de marca, precios por cantidad, sin promociones.';

ALTER TABLE product
    ADD COLUMN IF NOT EXISTS entrecueros_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS entrecueros_price_unit NUMERIC(12, 2) NULL,
    ADD COLUMN IF NOT EXISTS entrecueros_price_qty3 NUMERIC(12, 2) NULL,
    ADD COLUMN IF NOT EXISTS entrecueros_price_qty6 NUMERIC(12, 2) NULL,
    ADD COLUMN IF NOT EXISTS entrecueros_price_qty12 NUMERIC(12, 2) NULL;

COMMENT ON COLUMN product.entrecueros_enabled IS
    'Si true, el producto puede venderse en kioscos POS modalidad ENTRECUEROS.';
COMMENT ON COLUMN product.entrecueros_price_unit IS 'Precio unitario Entrecueros (1 pieza).';
COMMENT ON COLUMN product.entrecueros_price_qty3 IS 'Precio unitario Entrecueros desde 3 piezas.';
COMMENT ON COLUMN product.entrecueros_price_qty6 IS 'Precio unitario Entrecueros desde 6 piezas.';
COMMENT ON COLUMN product.entrecueros_price_qty12 IS 'Precio unitario Entrecueros desde 12 piezas.';

UPDATE locations
SET pos_mode = 'ENTRECUEROS',
    pos_opening_cash_amount = 500.00
WHERE id = 42
   OR LOWER(COALESCE(name, '')) LIKE '%entrecueros%';
