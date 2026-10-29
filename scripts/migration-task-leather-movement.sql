-- Cuero por producto: la salida del kardex guarda su ítem de tarea; una sola salida activa por ítem.
-- Correr antes de arrancar el backend que la usa (ddl-auto=validate). Se puede repetir.
BEGIN;
ALTER TABLE leather_movement ADD COLUMN IF NOT EXISTS task_item_id BIGINT;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_leather_movement_task_item') THEN
   ALTER TABLE leather_movement ADD CONSTRAINT fk_leather_movement_task_item
   FOREIGN KEY(task_item_id) REFERENCES task_item(id) ON DELETE RESTRICT;
 END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_leather_movement_active_task_item
    ON leather_movement(task_item_id) WHERE task_item_id IS NOT NULL AND movement_type = 'SALIDA';
COMMIT;
