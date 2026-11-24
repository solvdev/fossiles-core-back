-- Libro contable de ventas en línea: la autorización que anota Contabilidad pasa de 200 a 500 caracteres.
-- Correr ANTES de desplegar el back (si no, Postgres rechaza textos de más de 200).
-- Rollback: solo si ninguna fila pasa de 200 caracteres:
--   ALTER TABLE online_sale_accounting ALTER COLUMN payment_authorization TYPE VARCHAR(200);

ALTER TABLE online_sale_accounting ALTER COLUMN payment_authorization TYPE VARCHAR(500);
