-- Formatos de impresión: columna body para el cuerpo del formato.
-- Correr antes de arrancar el backend que la usa (ddl-auto=validate). Se puede repetir.
BEGIN;
ALTER TABLE print_format ADD COLUMN IF NOT EXISTS body TEXT;
COMMIT;
