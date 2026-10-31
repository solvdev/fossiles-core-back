-- Cuero por producto: la salida del kardex guarda el producto de la tarea que la recibió.
-- Correr ANTES de arrancar el backend (ddl-auto=validate). Se puede repetir.
-- El interruptor queda apagado: con 'false' todo funciona igual que antes.
BEGIN;

ALTER TABLE leather_movement ADD COLUMN IF NOT EXISTS task_item_id BIGINT;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_leather_movement_task_item') THEN
        -- SET NULL: si se borra el producto de la tarea, la salida queda en el kardex (el cuero sí salió).
        ALTER TABLE leather_movement ADD CONSTRAINT fk_leather_movement_task_item
            FOREIGN KEY (task_item_id) REFERENCES task_item(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Una sola salida activa por producto de tarea.
CREATE UNIQUE INDEX IF NOT EXISTS uq_leather_movement_active_task_item
    ON leather_movement(task_item_id) WHERE task_item_id IS NOT NULL AND movement_type = 'SALIDA';

INSERT INTO system_config (config_key, config_value, description, created_at, updated_at)
VALUES ('produccion.cuero.kardex_por_producto', 'false',
        'Cuero por producto: true descuenta el kardex al marcar cuero entregado en cada producto', NOW(), NOW())
ON CONFLICT (config_key) DO NOTHING;

COMMIT;
