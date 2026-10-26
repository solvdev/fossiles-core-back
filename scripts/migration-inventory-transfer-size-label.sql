-- Talla en traslados de inventario (obligatoria para cinchos).
-- Correr ANTES de desplegar el back: Hibernate valida el esquema (ddl-auto=validate)
-- y sin esta columna la aplicación no arranca.

ALTER TABLE inventory_transfer
    ADD COLUMN IF NOT EXISTS size_label VARCHAR(50);
