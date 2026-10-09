-- Índices para acelerar Dashboards > Ventas (kioskos, online, vendedor LF).
-- Idempotente: se puede correr varias veces. No se ejecuta solo (ddl-auto=validate).
--
-- Para no bloquear escrituras en producción, ejecutar cada sentencia por separado agregando
-- CONCURRENTLY (p. ej. CREATE INDEX CONCURRENTLY IF NOT EXISTS ...). CONCURRENTLY no puede correr
-- dentro de una transacción: con psql usar autocommit (sin BEGIN ni -1/--single-transaction).
--
-- El dashboard del vendedor LF usa idx_po_dashboard_date y idx_poi_po_id, que ya define
-- scripts/index-dashboard-date-range.sql (correr ese script si aún no existen).

CREATE INDEX IF NOT EXISTS idx_kiosk_sale_date_kiosk
ON kiosk_sale (sale_date, kiosk_location_id);

CREATE INDEX IF NOT EXISTS idx_online_sale_date
ON online_sale (sale_date);

CREATE INDEX IF NOT EXISTS idx_kiosk_sale_item_sale
ON kiosk_sale_item (kiosk_sale_id);

CREATE INDEX IF NOT EXISTS idx_online_sale_item_sale
ON online_sale_item (online_sale_id);
