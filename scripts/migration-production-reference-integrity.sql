-- Órdenes de producción: tareas e ítems de tarea solo apuntan a ítems de orden existentes, y una unidad
-- recibida en bodega impide borrar su ítem (RESTRICT en lugar de CASCADE). Se puede repetir.
-- Correr junto con el despliegue del backend que conserva los ítems al editar una orden: el backend
-- anterior los borra y los vuelve a crear, y estas llaves rechazarían esa edición.
-- NOT VALID conserva los casos históricos pendientes y exige referencias válidas para nuevas escrituras.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_task_item_production_order_item') THEN
   ALTER TABLE task_item ADD CONSTRAINT fk_task_item_production_order_item
   FOREIGN KEY(production_order_item_id) REFERENCES production_order_item(id) ON DELETE RESTRICT NOT VALID;
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_task_production_order_item') THEN
   ALTER TABLE task ADD CONSTRAINT fk_task_production_order_item
   FOREIGN KEY(production_order_item_id) REFERENCES production_order_item(id) ON DELETE RESTRICT NOT VALID;
 END IF;
END $$;
ALTER TABLE production_order_warehouse_unit DROP CONSTRAINT IF EXISTS production_order_warehouse_unit_production_order_item_id_fkey;
ALTER TABLE production_order_warehouse_unit ADD CONSTRAINT production_order_warehouse_unit_production_order_item_id_fkey
FOREIGN KEY(production_order_item_id) REFERENCES production_order_item(id) ON DELETE RESTRICT;
COMMIT;
