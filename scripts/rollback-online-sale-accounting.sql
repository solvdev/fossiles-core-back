-- Rollback de scripts/migration-online-sale-accounting.sql.
-- BORRA lo que Contabilidad haya anotado en el libro de ventas en línea. Respaldar antes:
--   \copy online_sale_accounting TO 'online_sale_accounting.csv' CSV HEADER
-- Desplegar primero el backend anterior (el actual valida que la tabla exista).

DROP TABLE IF EXISTS online_sale_accounting;
