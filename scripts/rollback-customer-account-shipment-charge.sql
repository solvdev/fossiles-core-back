-- Revierte migration-customer-account-shipment-charge.sql: vuelve a un solo CHARGE activo por orden.
-- Solo es posible si ninguna orden tiene mas de un CHARGE activo (los cargos por despacho de una orden con varios
-- envios lo impiden). En ese caso anule o consolide los cargos sobrantes antes de correrlo.
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f rollback-customer-account-shipment-charge.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;

-- Pre-check (solo lectura). Debe devolver 0 filas.
SELECT production_order_id, count(*) AS active_charges, array_agg(id ORDER BY id) AS entry_ids
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND production_order_id IS NOT NULL
GROUP BY production_order_id
HAVING count(*) > 1
ORDER BY production_order_id;

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    duplicate_orders integer;
BEGIN
    SELECT count(*) INTO duplicate_orders FROM (
        SELECT production_order_id
        FROM customer_account_entry
        WHERE entry_type = 'CHARGE'
          AND status <> 'VOID'
          AND production_order_id IS NOT NULL
        GROUP BY production_order_id
        HAVING count(*) > 1
    ) d;
    IF duplicate_orders > 0 THEN
        RAISE EXCEPTION
            'Rollback abortado: % ordenes con mas de un CHARGE activo (cargos por despacho). No se cambio nada.',
            duplicate_orders;
    END IF;
END $$;

DROP INDEX IF EXISTS uq_cae_one_active_charge_per_shipment;
DROP INDEX IF EXISTS uq_cae_one_active_charge_per_order;
CREATE UNIQUE INDEX uq_cae_one_active_charge_per_order
    ON customer_account_entry (production_order_id)
    WHERE entry_type = 'CHARGE' AND status <> 'VOID';

COMMIT;
