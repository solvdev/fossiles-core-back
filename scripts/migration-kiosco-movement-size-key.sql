-- Talla en el log de movimientos kiosco (para kardex/conteo por size).
-- Ejecutar manualmente en PostgreSQL (ddl-auto=validate).

ALTER TABLE kiosco_movement
    ADD COLUMN IF NOT EXISTS size_key VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_kiosco_movement_stock_size_created
    ON kiosco_movement (kiosco_stock_id, size_key, created_at DESC, id DESC);

COMMENT ON COLUMN kiosco_movement.size_key IS
    'Talla normalizada del movimiento (p. ej. envío FOSS). NULL = movimiento agregado / histórico sin talla.';

-- Backfill ENTRADA de recepción desde product_shipment_detail.size_label
-- (requiere flag admin porque kiosco_movement es append-only).
BEGIN;
SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

UPDATE kiosco_movement km
SET size_key = NULLIF(BTRIM(psd.size_label), '')
FROM product_shipment_detail psd
WHERE km.size_key IS NULL
  AND km.movement_type = 'ENTRADA'
  AND km.reference_id IS NOT NULL
  AND psd.shipment_id = km.reference_id
  AND NULLIF(BTRIM(COALESCE(psd.size_label, '')), '') IS NOT NULL
  AND km.reason IS NOT NULL
  AND substring(km.reason FROM 'SHIPMENT_RCPT:[^[:space:]]*#L([0-9]+)') IS NOT NULL
  AND substring(km.reason FROM 'SHIPMENT_RCPT:[^[:space:]]*#L([0-9]+)')::bigint = psd.id;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);
COMMIT;
