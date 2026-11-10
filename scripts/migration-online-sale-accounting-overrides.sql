-- Libro de ventas en línea: desde «Total con envío» hacia la derecha todo se puede editar en el libro.
-- Cada columna nueva es el valor que anota Contabilidad; vacía (NULL) = rige el dato de la venta.
-- online_sale NO se toca: corregir aquí no cambia la venta, el inventario ni Ventas en línea.
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Requiere scripts/migration-online-sale-accounting.sql (y -lock, -authorization). Idempotente.
-- Rollback:
--   ALTER TABLE online_sale_accounting
--     DROP COLUMN IF EXISTS total_amount, DROP COLUMN IF EXISTS shipping_cost, DROP COLUMN IF EXISTS net_amount,
--     DROP COLUMN IF EXISTS carrier, DROP COLUMN IF EXISTS sale_date, DROP COLUMN IF EXISTS social_network,
--     DROP COLUMN IF EXISTS email, DROP COLUMN IF EXISTS salesperson, DROP COLUMN IF EXISTS guide_number,
--     DROP COLUMN IF EXISTS shipment_number, DROP COLUMN IF EXISTS status;

ALTER TABLE online_sale_accounting
    ADD COLUMN IF NOT EXISTS total_amount    NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS shipping_cost   NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS net_amount      NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS carrier         VARCHAR(50),
    ADD COLUMN IF NOT EXISTS sale_date       DATE,
    ADD COLUMN IF NOT EXISTS social_network  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS email           VARCHAR(200),
    ADD COLUMN IF NOT EXISTS salesperson     VARCHAR(150),
    ADD COLUMN IF NOT EXISTS guide_number    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS shipment_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS status          VARCHAR(30);

COMMENT ON COLUMN online_sale_accounting.total_amount IS 'Total con envío anotado en el libro. Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.shipping_cost IS 'Envío cobrado anotado en el libro. Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.net_amount IS 'Sin envío anotado en el libro. Vacío = total − envío.';
COMMENT ON COLUMN online_sale_accounting.carrier IS 'Transporte anotado en el libro (MOTORISTA, FORZA, GUATEX…). Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.sale_date IS 'Fecha anotada en el libro. Vacío = la de la venta. El periodo se sigue filtrando por la fecha de la venta.';
COMMENT ON COLUMN online_sale_accounting.social_network IS 'Red social anotada en el libro. Vacío = la de la venta.';
COMMENT ON COLUMN online_sale_accounting.email IS 'Correo anotado en el libro. Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.salesperson IS 'Vendedor anotado en el libro (cuenta para comisiones). Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.guide_number IS 'Guía anotada en el libro. Vacío = la de la venta.';
COMMENT ON COLUMN online_sale_accounting.shipment_number IS 'No. de envío anotado en el libro. Vacío = el de la venta.';
COMMENT ON COLUMN online_sale_accounting.status IS 'Estado anotado en el libro (cuenta para devoluciones/anuladas en comisiones). Vacío = el de la venta.';
