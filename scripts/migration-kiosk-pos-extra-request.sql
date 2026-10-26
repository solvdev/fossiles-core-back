-- ---------------------------------------------------------------------------
-- POS kioscos: complemento extra (solicitud + autorizacion) y carritos en espera.
--
-- 1) kiosk_pos_extra_request: el cajero pide cobrar un monto extra POR PIEZA por un complemento
--    (descripcion libre). Admin/logistica aprueba o rechaza. Al cobrar, la venta consume la solicitud
--    (status USED + sale_id). El precio lo calcula el servidor; el cliente solo envia el id.
-- 2) kiosk_pos_held_cart: carrito guardado en el servidor mientras llega la autorizacion (o por pausa)
--    para atender a otro cliente. No es una venta: no consume correlativo, no mueve stock ni caja.
-- 3) kiosk_sale_item: guarda el extra cobrado por pieza, su descripcion y la solicitud que lo autorizo.
--
-- Ejecutar a mano ANTES de desplegar el backend (hibernate ddl-auto=validate exige tablas/columnas).
-- Es idempotente.
-- Revertir:
--   ALTER TABLE kiosk_sale_item DROP COLUMN extra_request_id, DROP COLUMN extra_amount, DROP COLUMN extra_description;
--   DROP TABLE kiosk_pos_held_cart; DROP TABLE kiosk_pos_extra_request;
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS kiosk_pos_extra_request (
    id                  BIGSERIAL PRIMARY KEY,
    kiosk_location_id   BIGINT        NOT NULL,
    product_id          BIGINT        NOT NULL,
    product_code        VARCHAR(60),
    product_name        VARCHAR(180),
    color_id            BIGINT,
    color_name          VARCHAR(120),
    size                VARCHAR(20),
    hardware_condition  VARCHAR(40),
    quantity            NUMERIC(12, 3) NOT NULL,
    extra_amount        NUMERIC(12, 2) NOT NULL,
    catalog_unit_price  NUMERIC(12, 2),
    description         VARCHAR(300)  NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    request_date        DATE          NOT NULL,
    requested_by        BIGINT        NOT NULL,
    requested_at        TIMESTAMP     NOT NULL,
    authorized_by       BIGINT,
    authorized_at       TIMESTAMP,
    rejection_reason    VARCHAR(1000),
    sale_id             BIGINT,
    used_at             TIMESTAMP,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT chk_kiosk_pos_extra_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'USED', 'CANCELLED')),
    CONSTRAINT chk_kiosk_pos_extra_request_amounts
        CHECK (quantity > 0 AND extra_amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_kiosk_pos_extra_request_kiosk_status
    ON kiosk_pos_extra_request (kiosk_location_id, status, request_date);

COMMENT ON TABLE kiosk_pos_extra_request IS
    'Solicitud de complemento extra en el POS de kioscos (monto por pieza). Requiere autorizacion de admin/logistica.';
COMMENT ON COLUMN kiosk_pos_extra_request.extra_amount IS 'Monto extra POR PIEZA (no por linea).';

CREATE TABLE IF NOT EXISTS kiosk_pos_held_cart (
    id                  BIGSERIAL PRIMARY KEY,
    kiosk_location_id   BIGINT       NOT NULL,
    created_by          BIGINT       NOT NULL,
    label               VARCHAR(160),
    cart_json           TEXT         NOT NULL,
    held_date           DATE         NOT NULL,
    created_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kiosk_pos_held_cart_kiosk_date
    ON kiosk_pos_held_cart (kiosk_location_id, held_date);

COMMENT ON TABLE kiosk_pos_held_cart IS
    'Carrito del POS en espera (ej. aguardando autorizacion de complemento). Se elimina al retomarlo; solo vale el dia.';

ALTER TABLE kiosk_sale_item ADD COLUMN IF NOT EXISTS extra_request_id BIGINT;
ALTER TABLE kiosk_sale_item ADD COLUMN IF NOT EXISTS extra_amount NUMERIC(12, 2);
ALTER TABLE kiosk_sale_item ADD COLUMN IF NOT EXISTS extra_description VARCHAR(300);

COMMENT ON COLUMN kiosk_sale_item.extra_amount IS
    'Extra cobrado POR PIEZA por complemento (ya incluido en unit_price). NULL = sin complemento';
