-- Descuento de colaborador por producto (compras de colaborador y envíos tipo planilla).
-- Vacío: el descuento general del 50 %. Ejecutar ANTES de desplegar el back (ddl-auto=validate).

BEGIN;

ALTER TABLE product ADD COLUMN IF NOT EXISTS employee_discount_percent NUMERIC(5,2);
ALTER TABLE product DROP CONSTRAINT IF EXISTS product_employee_discount_range;
ALTER TABLE product ADD CONSTRAINT product_employee_discount_range
    CHECK (employee_discount_percent IS NULL OR (employee_discount_percent >= 0 AND employee_discount_percent <= 100));

COMMIT;
