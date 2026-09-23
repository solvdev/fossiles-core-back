-- Índices opcionales para acelerar dashboard-v2 / dashboard-stats
-- (filtro por COALESCE de fechas). Correr en mantenimiento.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_po_dashboard_date
ON production_order (COALESCE(start_date, created_at::date));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_dashboard_date
ON task (COALESCE(scheduled_date, completed_at::date, created_at::date));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_poi_po_id
ON production_order_item (production_order_id);
