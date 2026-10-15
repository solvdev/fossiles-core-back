-- CARGO POR DESPACHO. Un cargo (CHARGE) por envio despachado en lugar de un cargo por orden completa.
-- Cambia el indice unico de cargos activos:
--   antes:  1 CHARGE activo por orden (uq_cae_one_active_charge_per_order, production_order_id)
--   ahora:  1 CHARGE activo por envio (uq_cae_one_active_charge_per_shipment, product_shipment_id)
--           + el cargo por orden completa anterior (sin envio) sigue siendo unico por orden
--             (uq_cae_one_active_charge_per_order, ahora solo con product_shipment_id IS NULL).
-- Hay que correrlo ANTES de desplegar el backend que crea cargos al despachar, en fosstest y despues en fossilesgt.
-- No modifica ningun movimiento: solo reemplaza indices. Los cargos por orden completa existentes se recortan a lo
-- despachado al leer (CustomerAccountService.visibleEntries); el libro no se toca.
-- Lock: ACCESS EXCLUSIVE sobre customer_account_entry durante la construccion de los indices (tabla pequena).
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f migration-customer-account-shipment-charge.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino, (SELECT count(*) FROM customer_account_entry) AS filas_ledger;

-- Pre-check (solo lectura). Debe devolver 0 filas: dos cargos activos del mismo envio impiden el indice nuevo.
SELECT product_shipment_id, count(*) AS active_charges, array_agg(id ORDER BY id) AS entry_ids
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND product_shipment_id IS NOT NULL
GROUP BY product_shipment_id
HAVING count(*) > 1
ORDER BY product_shipment_id;

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    duplicate_shipments integer;
BEGIN
    SELECT count(*) INTO duplicate_shipments FROM (
        SELECT product_shipment_id
        FROM customer_account_entry
        WHERE entry_type = 'CHARGE'
          AND status <> 'VOID'
          AND product_shipment_id IS NOT NULL
        GROUP BY product_shipment_id
        HAVING count(*) > 1
    ) d;
    IF duplicate_shipments > 0 THEN
        RAISE EXCEPTION
            'Cargo por despacho abortado: % envios con mas de un CHARGE activo. No se cambio nada.', duplicate_shipments;
    END IF;
END $$;

DROP INDEX IF EXISTS uq_cae_one_active_charge_per_order;
CREATE UNIQUE INDEX uq_cae_one_active_charge_per_order
    ON customer_account_entry (production_order_id)
    WHERE entry_type = 'CHARGE' AND status <> 'VOID' AND product_shipment_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_cae_one_active_charge_per_shipment
    ON customer_account_entry (product_shipment_id)
    WHERE entry_type = 'CHARGE' AND status <> 'VOID' AND product_shipment_id IS NOT NULL;

COMMIT;

-- Checks (solo lectura): ambos deben decir t.
SELECT to_regclass('uq_cae_one_active_charge_per_order') IS NOT NULL    AS indice_orden_ok,
       to_regclass('uq_cae_one_active_charge_per_shipment') IS NOT NULL AS indice_envio_ok;
