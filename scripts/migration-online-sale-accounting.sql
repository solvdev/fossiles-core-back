-- Libro de ventas en línea: datos que lleva Contabilidad por cada venta (envío real, comisión del transporte,
-- liquidación del motorista, faltantes y sobrantes). Van en una tabla aparte: online_sale no se toca.
-- Correr en fosstest y luego en fossilesgt ANTES de desplegar el backend (ddl-auto=validate).
-- Idempotente. Rollback: scripts/rollback-online-sale-accounting.sql

CREATE TABLE IF NOT EXISTS online_sale_accounting (
    id                   BIGSERIAL PRIMARY KEY,
    online_sale_id       BIGINT        NOT NULL REFERENCES online_sale (id) ON DELETE CASCADE,
    real_shipping_cost   NUMERIC(12, 2),
    carrier_commission   NUMERIC(12, 2),
    accounting_note      VARCHAR(1000),
    sent_date            DATE,
    received_date        DATE,
    payment_form         VARCHAR(100),
    liquidation_date     DATE,
    deposit_slip_number  VARCHAR(100),
    delivery_note        VARCHAR(500),
    real_deposit_amount  NUMERIC(12, 2),
    deposit_note         VARCHAR(500),
    shortage_amount      NUMERIC(12, 2),
    shortage_note        VARCHAR(500),
    reviewed             BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_by           BIGINT,
    CONSTRAINT uq_online_sale_accounting_sale UNIQUE (online_sale_id)
);

COMMENT ON TABLE online_sale_accounting IS
    'Datos de Contabilidad por venta en línea (libro de ventas). Una fila por venta, se crea al primer cambio.';
COMMENT ON COLUMN online_sale_accounting.real_shipping_cost IS 'Envío real: lo que cobró el transporte por llevar el paquete.';
COMMENT ON COLUMN online_sale_accounting.carrier_commission IS 'Comisión real del transporte por el cobro contra entrega.';
COMMENT ON COLUMN online_sale_accounting.sent_date IS 'Fecha en que se envió. Vacío = se usa online_sale.dispatched_at.';
COMMENT ON COLUMN online_sale_accounting.payment_form IS 'Forma de pago real anotada por Contabilidad. Vacío = la de la venta.';
COMMENT ON COLUMN online_sale_accounting.deposit_slip_number IS 'No. de boleta o referencia de la liquidación.';
COMMENT ON COLUMN online_sale_accounting.real_deposit_amount IS 'Valor real depositado.';
COMMENT ON COLUMN online_sale_accounting.shortage_amount IS 'Positivo = faltante (se descuenta de la comisión). Negativo = sobrante.';

-- Búsqueda por rango de fechas del libro (los mismos de scripts/index-sales-dashboard.sql, por si no se corrieron).
CREATE INDEX IF NOT EXISTS idx_online_sale_date ON online_sale (sale_date);
CREATE INDEX IF NOT EXISTS idx_online_sale_item_sale ON online_sale_item (online_sale_id);
