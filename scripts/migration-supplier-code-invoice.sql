-- Código de proveedor (PV001, PV002…) y factura del proveedor en la recepción de compras.
-- Ejecutar ANTES de desplegar el back. Es idempotente: se puede correr más de una vez.

BEGIN;

-- 1. Código del proveedor. Único; el back pone el siguiente número al crear uno nuevo.
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS code VARCHAR(10);

-- A los que no tienen código se les asigna uno por orden alfabético, después del último PV que exista.
-- Si luego se cargan los códigos del sistema anterior (Proveedores › Cargar códigos), esos reemplazan estos.
WITH last_code AS (
    SELECT COALESCE(MAX(substring(code FROM 3)::int), 0) AS n
    FROM supplier
    WHERE code ~ '^PV[0-9]+$'
), pending AS (
    SELECT id, row_number() OVER (ORDER BY lower(trim(name)), id) AS rn
    FROM supplier
    WHERE code IS NULL
)
UPDATE supplier s
SET code = 'PV' || lpad((last_code.n + pending.rn)::text, 3, '0')
FROM pending, last_code
WHERE s.id = pending.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_code ON supplier (code);

-- 2. Factura del proveedor en cada recepción. Con ella, el vencimiento en cuentas por pagar se cuenta desde la fecha
--    de la factura (y no desde la fecha en que se recibió).
ALTER TABLE material_receipt ADD COLUMN IF NOT EXISTS invoice_series VARCHAR(20);
ALTER TABLE material_receipt ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(40);
ALTER TABLE material_receipt ADD COLUMN IF NOT EXISTS invoice_date DATE;

COMMIT;

-- Revisión
SELECT count(*) AS proveedores, count(code) AS con_codigo, min(code) AS primero, max(code) AS ultimo FROM supplier;
