-- Promociones dinámicas (tipo RULES) + aprobación + descuento por pieza.
-- CORRER ANTES DE DESPLEGAR EL BACK (ddl-auto=validate). Es idempotente.

BEGIN;

-- Regla armada por bloques (JSON): cuándo, a qué piezas, qué descuento y límites.
ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS rule_config TEXT;

-- Estado: DRAFT (borrador, solo simulador) o APPROVED (vigente en el POS).
ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;
ALTER TABLE kiosk_promotion
    ADD COLUMN IF NOT EXISTS approved_by BIGINT;

-- Las promociones que ya existen siguen funcionando igual: quedan aprobadas.
UPDATE kiosk_promotion
SET status = 'APPROVED'
WHERE status IS NULL OR TRIM(status) = '';

-- Descuento que llevó cada línea (solo promociones RULES; null = se reparte como antes).
ALTER TABLE kiosk_sale_item
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(12, 2);
ALTER TABLE kiosk_sale_item
    ADD COLUMN IF NOT EXISTS discount_note VARCHAR(200);

-- Copia de la regla usada en la venta: si luego editan la promoción, la venta conserva la suya.
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS promotion_rule_snapshot TEXT;

COMMIT;
