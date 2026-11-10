-- Libro de ventas en línea: autorización de pago editable desde el libro (números y texto).
-- Si está vacía, el libro muestra la autorización original de la venta (online_sale.payment_authorization, que no se toca).
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-online-sale-accounting.sql. Idempotente.
-- Rollback: ALTER TABLE online_sale_accounting DROP COLUMN IF EXISTS payment_authorization;

ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS payment_authorization VARCHAR(200);

COMMENT ON COLUMN online_sale_accounting.payment_authorization IS 'Autorización de pago anotada por Contabilidad. Vacío = la de la venta.';
