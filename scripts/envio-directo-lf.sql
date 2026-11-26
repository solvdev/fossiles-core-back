-- Envío directo a clientes de Luis Felipe (sin OP), 2026-11-26.
-- Correr ANTES de desplegar el back (ddl-auto=validate: sin estas columnas el back no arranca).
-- Idempotente. Termina en ROLLBACK para revisarlo: cámbielo a COMMIT para aplicarlo.

BEGIN;

ALTER TABLE product_shipment
    ADD COLUMN IF NOT EXISTS direct_sale_customer_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS direct_sale_kind VARCHAR(10) NULL,
    ADD COLUMN IF NOT EXISTS direct_sale_source VARCHAR(20) NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_shipment_direct_sale_customer') THEN
        ALTER TABLE product_shipment
            ADD CONSTRAINT fk_product_shipment_direct_sale_customer
            FOREIGN KEY (direct_sale_customer_id) REFERENCES customer(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_shipment_direct_sale_kind') THEN
        ALTER TABLE product_shipment
            ADD CONSTRAINT chk_product_shipment_direct_sale_kind
            CHECK (direct_sale_kind IS NULL OR direct_sale_kind IN ('OPV', 'OPC'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_shipment_direct_sale_source') THEN
        ALTER TABLE product_shipment
            ADD CONSTRAINT chk_product_shipment_direct_sale_source
            CHECK (direct_sale_source IS NULL OR direct_sale_source IN ('PT', 'RETURNS'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_product_shipment_direct_sale_customer
    ON product_shipment (direct_sale_customer_id)
    WHERE direct_sale_customer_id IS NOT NULL;

-- Revisión: las columnas existen y ningún envío viejo quedó marcado.
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'product_shipment' AND column_name LIKE 'direct_sale_%'
ORDER BY column_name;

SELECT COUNT(*) AS envios_directos FROM product_shipment WHERE direct_sale_customer_id IS NOT NULL;

ROLLBACK;
