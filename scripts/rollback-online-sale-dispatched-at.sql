-- Deshace migration-online-sale-dispatched-at.sql.
-- Correr DESPUÉS de volver a desplegar un backend que no mapea dispatched_at; si no, el arranque falla.
ALTER TABLE online_sale DROP COLUMN IF EXISTS dispatched_at;
