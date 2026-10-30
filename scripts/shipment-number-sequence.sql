-- Correlativos LF_ENVP (ENVP-nnnnn) y LF_OPC ({OPCODE}-ENV-n).
-- DataGrip: corre el archivo completo. Por defecto termina en ROLLBACK (vista previa).
-- Para aplicar, cambie el ROLLBACK final por COMMIT después de que Index revise las previews.
-- No lo ejecuta el backend (ddl-auto=validate). Eduardo lo corre a mano en fosstest, luego prod.
-- Rollback: scripts/shipment-number-sequence-rollback.sql

BEGIN;
SET LOCAL lock_timeout = '5s';

-- Guardia: unique en product_shipment.shipment_number (constraint o índice).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'product_shipment'
          AND c.contype IN ('u', 'p')
          AND pg_get_constraintdef(c.oid) ILIKE '%shipment_number%'
    ) AND NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE tablename = 'product_shipment'
          AND indexdef ILIKE '%UNIQUE%'
          AND indexdef ILIKE '%shipment_number%'
    ) THEN
        RAISE EXCEPTION
            'Falta el índice único en product_shipment.shipment_number. No se creó shipment_number_counter.';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS shipment_number_counter (
    type text PRIMARY KEY,
    last_value bigint NOT NULL
);

CREATE TEMP TABLE envp_preview ON COMMIT DROP AS
SELECT
    coalesce((
        SELECT max((regexp_match(s.shipment_number, '^ENVP-(\d+)$'))[1]::bigint)
        FROM product_shipment s
        WHERE s.shipment_number ~ '^ENVP-\d+$'
    ), 0) AS max_envp_shipment,
    coalesce((
        SELECT max((regexp_match(po.vendor_shipment_number, '^ENVP-(\d+)$'))[1]::bigint)
        FROM production_order po
        WHERE po.vendor_shipment_number ~ '^ENVP-\d+$'
    ), 0) AS max_envp_order;

CREATE TEMP TABLE lf_opc_matched ON COMMIT DROP AS
SELECT
    s.id AS shipment_id,
    s.shipment_number,
    po.code AS order_code,
    (regexp_match(s.shipment_number, '-ENV-(\d+)$'))[1]::bigint AS seq
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
WHERE upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
  AND upper(btrim(coalesce(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
  AND s.shipment_number !~ '^ENVP-'
  AND regexp_replace(
        upper(translate(coalesce(po.customer_name, ''),
            'áéíóúüñÁÉÍÓÚÜÑàèìòùÀÈÌÒÙ',
            'AEIOUUNAEIOUUNAEIOUAEIOU')),
        '[^A-Z0-9]', '', 'g') NOT LIKE '%ENTRECUEROS%'
  AND po.code IS NOT NULL
  AND btrim(po.code) <> ''
  AND s.shipment_number ~ ('^' || regexp_replace(upper(btrim(po.code)), '([\\[\\](){}.+*?^$|\\\\])', '\\\1', 'g')
      || '-ENV-\d+$');

CREATE TEMP TABLE lf_opc_unmatched ON COMMIT DROP AS
SELECT
    s.id AS shipment_id,
    s.shipment_number,
    po.code AS order_code
FROM product_shipment s
JOIN production_order po ON po.id = s.production_order_id
WHERE upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
  AND upper(btrim(coalesce(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
  AND regexp_replace(
        upper(translate(coalesce(po.customer_name, ''),
            'áéíóúüñÁÉÍÓÚÜÑàèìòùÀÈÌÒÙ',
            'AEIOUUNAEIOUUNAEIOUAEIOU')),
        '[^A-Z0-9]', '', 'g') NOT LIKE '%ENTRECUEROS%'
  AND NOT EXISTS (
      SELECT 1 FROM lf_opc_matched m WHERE m.shipment_id = s.id
  );

SELECT 'PREVIEW_ENVP' AS seccion,
       max_envp_shipment,
       max_envp_order,
       greatest(max_envp_shipment, max_envp_order) AS lf_envp_seed
FROM envp_preview;

SELECT 'PREVIEW_LF_OPC' AS seccion,
       coalesce(max(seq), 0) AS lf_opc_max,
       count(*) AS lf_opc_matched_count
FROM lf_opc_matched;

SELECT 'PREVIEW_LF_OPC_UNMATCHED' AS seccion,
       shipment_number,
       order_code
FROM lf_opc_unmatched
ORDER BY shipment_number;

INSERT INTO shipment_number_counter (type, last_value)
SELECT 'LF_ENVP', greatest(max_envp_shipment, max_envp_order)
FROM envp_preview
ON CONFLICT (type) DO UPDATE
SET last_value = greatest(shipment_number_counter.last_value, excluded.last_value);

INSERT INTO shipment_number_counter (type, last_value)
SELECT 'LF_OPC', coalesce(max(seq), 0)
FROM lf_opc_matched
ON CONFLICT (type) DO UPDATE
SET last_value = greatest(shipment_number_counter.last_value, excluded.last_value);

SELECT 'COUNTERS' AS seccion, type, last_value
FROM shipment_number_counter
WHERE type IN ('LF_ENVP', 'LF_OPC')
ORDER BY type;

-- Vista previa: cambie a COMMIT para aplicar.
ROLLBACK;
