-- Libro de ventas en línea: bloqueo de líneas ya revisadas (no admiten más cambios desde el libro).
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-online-sale-accounting.sql. Idempotente.
-- Rollback: ALTER TABLE online_sale_accounting DROP COLUMN IF EXISTS locked, DROP COLUMN IF EXISTS locked_at,
--           DROP COLUMN IF EXISTS locked_by, DROP COLUMN IF EXISTS locked_by_name;

ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS locked         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS locked_at      TIMESTAMP;
ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS locked_by      BIGINT;
ALTER TABLE online_sale_accounting ADD COLUMN IF NOT EXISTS locked_by_name VARCHAR(150);

COMMENT ON COLUMN online_sale_accounting.locked IS 'TRUE = línea bloqueada: el backend rechaza cualquier edición hasta que un administrador la desbloquee.';
COMMENT ON COLUMN online_sale_accounting.locked_at IS 'Cuándo se bloqueó (hora de Guatemala).';
COMMENT ON COLUMN online_sale_accounting.locked_by IS 'Usuario que bloqueó (users.id).';
COMMENT ON COLUMN online_sale_accounting.locked_by_name IS 'Nombre de usuario de quien bloqueó, copiado al bloquear.';
