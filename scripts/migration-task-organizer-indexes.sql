-- Run manually against PostgreSQL when deploying (JPA ddl-auto=validate)
-- Organizador de tareas: índices para el modelo de "cantidad restante" por ítem de OP
-- (SUM(task_item.quantity) agrupado por production_order_item_id) y para el backlog
-- de tareas PENDING atrasadas/sin fecha.

CREATE INDEX IF NOT EXISTS idx_task_item_production_order_item_id
    ON task_item (production_order_item_id);

CREATE INDEX IF NOT EXISTS idx_task_status_scheduled_date
    ON task (status, scheduled_date);
