-- Duplicate-charge cleanup for LF receivables. Run on each database AFTER phase 1 and AFTER #120
-- is the code serving that database, and BEFORE phase 2.
--   fosstest first (develop), then fossilesgt (main). One database per run.
--
-- Scope: production orders with more than one ACTIVE CHARGE, or whose active charge amount is not the
-- product total (shipping mixed into the charge, or a charge that does not match the quote). Charges
-- with no production_order_id are listed under SIN_ORDEN and are not updated.
-- An order that already has exactly one active charge equal to the product total, and one active
-- adjustment per real shipment with a positive shipping_cost, is skipped. A second run reports nothing
-- to correct. REVIEW rows (not auto-fixed) are printed again until someone handles them.
--
-- Amounts are recomputed. They are never copied from the old charges.
--   products = ProductionOrderItemPricing.itemSubtotal (quantity * unit price, or sizes_data quantities
--              times unit_prices_json / unit_price / catalog, plus cincho surcharge 44-48 = 50 and 50+ = 100)
--   shipping = sum of product_shipment.shipping_cost > 0. Shipment status is not a filter:
--              the real statuses are DRAFT, CONFIRMED, SENT and DELIVERED.
-- An order is auto-fixed only when the active debit after (one charge for the product total
-- + adjustments already on the order + one new adjustment per shipment that does not have one)
-- equals the active debit before. Any other difference is REVIEW, with debito_antes,
-- debito_despues and delta. Existing CHARGE_ADJUSTMENT rows count. A shipment that already
-- has one is not given a second. Shipping that was never billed is not added when that
-- would change the debit.
-- Open, not decided here (REVIEW, never auto-fixed): whether packing that main folded into
-- the old charges is still billed, and whether a DRAFT shipment's shipping_cost is billed
-- now or only once it is dispatched.
--
-- Never UPDATE a charge amount. If the oldest charge (entry_date, then id) equals the product total, keep
-- it and void the other active charges. Otherwise void every active charge on the order and INSERT one
-- CHARGE for the product total. Entry date of that new charge is the oldest charge's entry_date.
--
-- Fixed tags (snapshot-saldos-cxc.sql matches them exactly; do not prefix or append):
--   void_reason  LIMPIEZA-CXC-DUPLICADOS-2026-10
--   description  LIMPIEZA-CXC-CARGO-NUEVO-2026-10     (inserted CHARGE only)
--   description  LIMPIEZA-CXC-AJUSTE-ENVIO-2026-10    (inserted CHARGE_ADJUSTMENT only)
--
-- One CHARGE_ADJUSTMENT per real shipment with shipping_cost > 0, linked to the survivor, copying the
-- order and OPV/OPC kind. Shipments that already have an active adjustment are not inserted again; if
-- that adjustment points at a charge this script voids, it is re-pointed to the survivor.
-- PAYMENT, CREDIT_NOTE and RETURN on a voided charge are re-pointed to the survivor (charge, order, kind).
-- reassigned_from_entry_id is set to the charge they left, and an existing value is kept.
--
-- Dry run by default (ROLLBACK). Apply only with -v aplicar=si (COMMIT).
-- Do NOT pass psql -1: this file has its own BEGIN/COMMIT, and -1 reports
-- "already/no transaction in progress".
-- A dry run still consumes ids: customer_account_entry_id_seq is not transactional, so the
-- only leftover is a gap. created_by and voided_by are bigint with no system user row, so
-- they stay null. The run marker is LIMPIEZA-CXC-2026-10, stored as the void_reason and
-- the description tags below (those columns cannot hold that text).
-- Usage:
--   psql -v ON_ERROR_STOP=1 -d fosstest -f cleanup-customer-account-lf-duplicates.sql
--   review PLAN, REVIEW (debitos y delta), OVERPAID, SIN_ORDEN, PHASE2_BLOCKERS
--   and BALANCE_CHECK (customer_id only; no names)
--   psql -v ON_ERROR_STOP=1 -v aplicar=si -d fosstest -f cleanup-customer-account-lf-duplicates.sql
-- PHASE2_BLOCKERS is the last section in both modes. It counts what phase 2 still refuses after these
-- statements: orders with more than one active charge, and active charges or adjustments with no order.
-- A dry run rolls those statements back, so that list is what would remain if you then apply.
-- Then snapshot with the three tags and run reconcile-cleanup-balances.sql (0 rows) before phase 2.
-- Phase 2 aborts while either blocker count is above zero.
\set ON_ERROR_STOP on

\if :{?aplicar}
\else
  \set aplicar no
\endif
SELECT CASE WHEN :'aplicar' = 'si' THEN 'yes' ELSE 'no' END AS limpieza_commit \gset

BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE customer_account_entry IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'customer_account_entry'
          AND column_name = 'reassigned_from_entry_id'
    ) THEN
        RAISE EXCEPTION 'Falta reassigned_from_entry_id. Correr fase 1 antes de la limpieza.';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_customer_account_entry_type'
          AND pg_get_constraintdef(oid) LIKE '%CHARGE_ADJUSTMENT%'
    ) THEN
        RAISE EXCEPTION 'Falta CHARGE_ADJUSTMENT. Correr fase 1 antes de la limpieza.';
    END IF;
    IF to_regclass('uq_cae_one_active_adjustment_per_shipment') IS NULL THEN
        RAISE EXCEPTION 'Falta el indice de un ajuste por envio. Correr fase 1 antes de la limpieza.';
    END IF;
END $$;

-- Java ProductInventorySizesJson.toBd: JSON null and non-numeric are 0 (courtesy), not a fallback.
CREATE OR REPLACE FUNCTION pg_temp.lf_explicit_price(p_value jsonb) RETURNS numeric
LANGUAGE plpgsql AS $$
DECLARE
    parsed numeric;
BEGIN
    IF p_value IS NULL OR jsonb_typeof(p_value) = 'null' THEN
        RETURN 0;
    END IF;
    BEGIN
        parsed := (p_value #>> '{}')::numeric;
    EXCEPTION WHEN OTHERS THEN
        RETURN 0;
    END;
    IF parsed IS NULL THEN
        RETURN 0;
    END IF;
    RETURN parsed;
END;
$$;

CREATE OR REPLACE FUNCTION pg_temp.lf_item_subtotal(
    p_quantity integer,
    p_unit_price numeric,
    p_sizes text,
    p_unit_prices text,
    p_catalog numeric
) RETURNS numeric
LANGUAGE plpgsql AS $$
DECLARE
    sizes jsonb;
    prices jsonb;
    rec record;
    qty_raw numeric;
    qty integer;
    explicit numeric;
    base numeric;
    surcharge numeric;
    size_num integer;
    total numeric := 0;
    has_sizes boolean := false;
    price_key text;
BEGIN
    IF p_sizes IS NOT NULL AND btrim(p_sizes) <> '' THEN
        BEGIN
            sizes := p_sizes::jsonb;
            has_sizes := jsonb_typeof(sizes) = 'object' AND sizes <> '{}'::jsonb;
        EXCEPTION WHEN OTHERS THEN
            has_sizes := false;
        END;
    END IF;

    IF p_unit_prices IS NOT NULL AND btrim(p_unit_prices) <> '' THEN
        BEGIN
            prices := p_unit_prices::jsonb;
            IF jsonb_typeof(prices) <> 'object' THEN
                prices := NULL;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            prices := NULL;
        END;
    END IF;

    IF has_sizes THEN
        FOR rec IN SELECT e.key AS k, e.value AS val FROM jsonb_each(sizes) e
        LOOP
            BEGIN
                qty_raw := (rec.val #>> '{}')::numeric;
            EXCEPTION WHEN OTHERS THEN
                CONTINUE;
            END;
            IF qty_raw IS NULL OR qty_raw <= 0 THEN
                CONTINUE;
            END IF;
            qty := round(qty_raw)::integer;
            IF qty <= 0 THEN
                CONTINUE;
            END IF;
            explicit := NULL;
            IF prices IS NOT NULL THEN
                IF jsonb_exists(prices, btrim(rec.k)) THEN
                    explicit := pg_temp.lf_explicit_price(prices -> btrim(rec.k));
                ELSE
                    FOR price_key IN SELECT jsonb_object_keys(prices)
                    LOOP
                        IF upper(btrim(price_key)) = upper(btrim(rec.k)) THEN
                            explicit := pg_temp.lf_explicit_price(prices -> price_key);
                            EXIT;
                        END IF;
                    END LOOP;
                END IF;
            END IF;
            IF explicit IS NOT NULL THEN
                base := explicit;
                surcharge := 0;
            ELSE
                IF p_unit_price IS NOT NULL AND p_unit_price > 0 THEN
                    base := p_unit_price;
                ELSIF p_catalog IS NOT NULL AND p_catalog > 0 THEN
                    base := p_catalog;
                ELSE
                    base := 0;
                END IF;
                surcharge := 0;
                IF base > 0 THEN
                    BEGIN
                        size_num := btrim(rec.k)::integer;
                        IF size_num >= 50 THEN
                            surcharge := 100;
                        ELSIF size_num >= 44 THEN
                            surcharge := 50;
                        END IF;
                    EXCEPTION WHEN OTHERS THEN
                        surcharge := 0;
                    END;
                END IF;
            END IF;
            total := total + (base + surcharge) * qty;
        END LOOP;
        RETURN round(total, 2);
    END IF;

    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RETURN 0;
    END IF;
    IF p_unit_price IS NOT NULL AND p_unit_price > 0 THEN
        base := p_unit_price;
    ELSIF p_catalog IS NOT NULL AND p_catalog > 0 THEN
        base := p_catalog;
    ELSE
        base := 0;
    END IF;
    RETURN round(base * p_quantity, 2);
END;
$$;

CREATE TEMP TABLE saldo_antes ON COMMIT DROP AS
SELECT customer_id,
       round(COALESCE(sum(
           CASE
               WHEN upper(coalesce(status, '')) <> 'ACTIVE' THEN 0
               WHEN upper(entry_type) IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT') THEN COALESCE(amount, 0)
               WHEN upper(entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN
                   -1 * CASE
                       WHEN gross_collected_amount > 0 THEN gross_collected_amount
                       WHEN payment_discount_amount > 0 THEN COALESCE(amount, 0) + payment_discount_amount
                       ELSE COALESCE(amount, 0)
                   END
               ELSE 0
           END
       ), 0), 2) AS saldo
FROM customer_account_entry
GROUP BY customer_id;

CREATE TEMP TABLE plan (
    production_order_id bigint PRIMARY KEY,
    customer_id bigint,
    accion text NOT NULL,
    products numeric(15, 2),
    shipping numeric(15, 2),
    charge_sum numeric(15, 2),
    oldest_id bigint,
    oldest_amount numeric(15, 2),
    oldest_date date,
    order_kind varchar(10),
    adj_sum numeric(15, 2),
    missing_shipping numeric(15, 2),
    document_number varchar(50),
    invoice_number varchar(50),
    vendor_shipment_number varchar(30),
    survivor_id bigint,
    motivo text
) ON COMMIT DROP;

INSERT INTO plan (
    production_order_id, customer_id, accion, products, shipping, charge_sum,
    oldest_id, oldest_amount, oldest_date, order_kind, adj_sum, missing_shipping,
    document_number, invoice_number, vendor_shipment_number, motivo
)
WITH active_charges AS (
    SELECT *
    FROM customer_account_entry
    WHERE upper(entry_type) = 'CHARGE'
      AND upper(coalesce(status, '')) = 'ACTIVE'
      AND production_order_id IS NOT NULL
),
grouped AS (
    SELECT production_order_id,
           count(*) AS n,
           round(sum(amount), 2) AS charge_sum,
           (array_agg(id ORDER BY entry_date, id))[1] AS oldest_id,
           (array_agg(round(amount, 2) ORDER BY entry_date, id))[1] AS oldest_amount,
           (array_agg(entry_date ORDER BY entry_date, id))[1] AS oldest_date,
           (array_agg(customer_id ORDER BY entry_date, id))[1] AS customer_id,
           (array_agg(invoice_number ORDER BY entry_date, id))[1] AS oldest_invoice,
           (array_agg(document_number ORDER BY entry_date, id))[1] AS oldest_document,
           (array_agg(vendor_shipment_number ORDER BY entry_date, id))[1] AS oldest_vsn,
           count(DISTINCT customer_id) AS customer_n
    FROM active_charges
    GROUP BY production_order_id
),
ship AS (
    SELECT production_order_id,
           round(COALESCE(sum(shipping_cost), 0), 2) AS shipping
    FROM product_shipment
    WHERE shipping_cost > 0
    GROUP BY production_order_id
),
priced AS (
    SELECT i.production_order_id,
           round(COALESCE(sum(pg_temp.lf_item_subtotal(
               i.quantity,
               i.unit_price,
               i.sizes_data,
               i.unit_prices_json,
               CASE
                   WHEN upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
                    AND upper(trim(coalesce(po.order_type, ''))) NOT IN ('INTERNA', 'CLIENTE_KIOSKO')
                    AND pr.seller_price > 0 THEN pr.seller_price
                   WHEN pr.sale_price > 0 THEN pr.sale_price
                   WHEN pr.discounted_price > 0 THEN pr.discounted_price
                   ELSE 0
               END
           )), 0), 2) AS products
    FROM production_order_item i
    JOIN production_order po ON po.id = i.production_order_id
    LEFT JOIN product pr ON pr.id = i.product_id
    GROUP BY i.production_order_id
),
quote AS (
    SELECT g.production_order_id,
           g.n,
           g.charge_sum,
           g.oldest_id,
           g.oldest_amount,
           g.oldest_date,
           g.customer_id,
           g.customer_n,
           COALESCE(pr.products, 0) AS products,
           COALESCE(s.shipping, 0) AS shipping,
           po.id IS NOT NULL AS has_order,
           upper(trim(coalesce(po.status, ''))) = 'CANCELLED' AS cancelled,
           po.customer_id IS DISTINCT FROM g.customer_id AS other_customer,
           COALESCE((SELECT round(sum(a.amount), 2) FROM customer_account_entry a
                      WHERE a.production_order_id = g.production_order_id
                        AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                        AND upper(coalesce(a.status, '')) = 'ACTIVE'), 0) AS adj_sum,
           COALESCE((SELECT round(sum(round(x.shipping_cost, 2)), 2) FROM product_shipment x
                      WHERE x.production_order_id = g.production_order_id
                        AND x.shipping_cost > 0
                        AND NOT EXISTS (SELECT 1 FROM customer_account_entry a
                                         WHERE a.product_shipment_id = x.id
                                           AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                                           AND upper(coalesce(a.status, '')) <> 'VOID')), 0) AS missing_shipping,
           EXISTS (
               SELECT 1 FROM product_shipment ps
               WHERE ps.production_order_id = g.production_order_id
                 AND ps.shipping_cost > 0
                 AND upper(trim(coalesce(ps.status, ''))) = 'DRAFT'
                 AND NOT EXISTS (SELECT 1 FROM customer_account_entry a
                                  WHERE a.product_shipment_id = ps.id
                                    AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                                    AND upper(coalesce(a.status, '')) <> 'VOID')
           ) AS draft_unbilled,
           COALESCE(g.oldest_document, po.code) AS document_number,
           g.oldest_invoice AS invoice_number,
           COALESCE(g.oldest_vsn, po.vendor_shipment_number) AS vendor_shipment_number,
           CASE
               WHEN upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
                AND upper(trim(coalesce(po.order_type, ''))) NOT IN ('INTERNA', 'CLIENTE_KIOSKO')
                   THEN CASE
                       WHEN upper(trim(coalesce(po.order_type, ''))) IN
                            ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS', 'MARCAS')
                           THEN 'OPC'
                       ELSE 'OPV'
                   END
               ELSE COALESCE(NULLIF(upper(trim(po.order_type)), ''), 'OP')
           END AS order_kind
    FROM grouped g
    LEFT JOIN production_order po ON po.id = g.production_order_id
    LEFT JOIN priced pr ON pr.production_order_id = g.production_order_id
    LEFT JOIN ship s ON s.production_order_id = g.production_order_id
),
expected AS (
    SELECT ps.production_order_id,
           ps.id AS shipment_id,
           round(ps.shipping_cost, 2) AS shipping_cost
    FROM product_shipment ps
    WHERE ps.shipping_cost > 0
),
clean AS (
    SELECT q.production_order_id
    FROM quote q
    WHERE q.n = 1
      AND q.has_order
      AND NOT q.cancelled
      AND NOT q.other_customer
      AND NOT q.draft_unbilled
      AND q.customer_n = 1
      AND q.oldest_amount = q.products
      AND (
          SELECT count(*) FROM expected e WHERE e.production_order_id = q.production_order_id
      ) = (
          SELECT count(*)
          FROM customer_account_entry a
          WHERE a.production_order_id = q.production_order_id
            AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
            AND upper(coalesce(a.status, '')) = 'ACTIVE'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM expected e
          WHERE e.production_order_id = q.production_order_id
            AND NOT EXISTS (
                SELECT 1
                FROM customer_account_entry a
                WHERE a.product_shipment_id = e.shipment_id
                  AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                  AND upper(coalesce(a.status, '')) = 'ACTIVE'
                  AND a.applied_to_entry_id = q.oldest_id
                  AND round(a.amount, 2) = e.shipping_cost
            )
      )
)
SELECT q.production_order_id,
       q.customer_id,
       CASE
           WHEN NOT q.has_order THEN 'REVIEW'
           WHEN q.cancelled THEN 'REVIEW'
           WHEN q.other_customer THEN 'REVIEW'
           WHEN q.products <= 0 THEN 'REVIEW'
           WHEN q.customer_n <> 1 THEN 'REVIEW'
           WHEN q.draft_unbilled THEN 'REVIEW'
           WHEN q.charge_sum + q.adj_sum <> q.products + q.adj_sum + q.missing_shipping THEN 'REVIEW'
           WHEN EXISTS (
               SELECT 1
               FROM customer_account_entry a
               JOIN product_shipment ps ON ps.id = a.product_shipment_id
               WHERE a.production_order_id = q.production_order_id
                 AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                 AND upper(coalesce(a.status, '')) = 'ACTIVE'
                 AND round(a.amount, 2) IS DISTINCT FROM round(ps.shipping_cost, 2)
           ) THEN 'REVIEW'
           WHEN q.oldest_amount = q.products THEN 'CONSERVAR'
           ELSE 'NUEVO'
       END,
       q.products,
       q.shipping,
       q.charge_sum,
       q.oldest_id,
       q.oldest_amount,
       q.oldest_date,
       left(q.order_kind, 10),
       q.adj_sum,
       q.missing_shipping,
       left(q.document_number, 50),
       left(q.invoice_number, 50),
       left(q.vendor_shipment_number, 30),
       CASE
           WHEN NOT q.has_order THEN 'sin fila de orden'
           WHEN q.cancelled THEN 'orden cancelada'
           WHEN q.other_customer THEN 'la orden es de otro cliente'
           WHEN q.products <= 0 THEN 'productos no calculables'
           WHEN q.customer_n <> 1 THEN 'cargos de clientes distintos'
           WHEN q.draft_unbilled THEN 'envio en borrador: no se decide si se cobra ahora o al despacho'
           WHEN q.charge_sum + q.adj_sum <> q.products + q.adj_sum + q.missing_shipping
               THEN 'cambiaria el saldo en ' || ((q.products + q.missing_shipping) - q.charge_sum)::text
           WHEN EXISTS (
               SELECT 1
               FROM customer_account_entry a
               JOIN product_shipment ps ON ps.id = a.product_shipment_id
               WHERE a.production_order_id = q.production_order_id
                 AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                 AND upper(coalesce(a.status, '')) = 'ACTIVE'
                 AND round(a.amount, 2) IS DISTINCT FROM round(ps.shipping_cost, 2)
           ) THEN 'ajuste existente distinto al costo de envio'
           ELSE NULL
       END
FROM quote q
WHERE NOT EXISTS (
    SELECT 1 FROM clean c WHERE c.production_order_id = q.production_order_id
);

UPDATE plan SET survivor_id = oldest_id WHERE accion = 'CONSERVAR';

CREATE TEMP TABLE creditos_movidos (
    entry_id bigint PRIMARY KEY,
    production_order_id bigint NOT NULL,
    from_charge_id bigint NOT NULL
) ON COMMIT DROP;

CREATE TEMP TABLE ajustes_reapuntados (
    entry_id bigint PRIMARY KEY,
    production_order_id bigint NOT NULL,
    from_charge_id bigint NOT NULL
) ON COMMIT DROP;

CREATE TEMP TABLE ajustes_nuevos (
    entry_id bigint PRIMARY KEY,
    production_order_id bigint NOT NULL,
    shipment_id bigint NOT NULL,
    amount numeric(15, 2) NOT NULL
) ON COMMIT DROP;

INSERT INTO creditos_movidos (entry_id, production_order_id, from_charge_id)
SELECT c.id, p.production_order_id, c.applied_to_entry_id
FROM plan p
JOIN customer_account_entry ch
  ON ch.production_order_id = p.production_order_id
 AND upper(ch.entry_type) = 'CHARGE'
 AND upper(coalesce(ch.status, '')) = 'ACTIVE'
 AND (p.accion = 'NUEVO' OR ch.id IS DISTINCT FROM p.survivor_id)
JOIN customer_account_entry c
  ON c.applied_to_entry_id = ch.id
 AND upper(c.entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN')
 AND upper(coalesce(c.status, '')) = 'ACTIVE'
WHERE p.accion IN ('CONSERVAR', 'NUEVO');

-- Same row: point the live adjustment at the survivor. Includes adjustments that already
-- sit on a VOID charge of this order (those made every later run look unfinished).
INSERT INTO ajustes_reapuntados (entry_id, production_order_id, from_charge_id)
SELECT a.id, p.production_order_id, a.applied_to_entry_id
FROM plan p
JOIN customer_account_entry a
  ON a.production_order_id = p.production_order_id
 AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
 AND upper(coalesce(a.status, '')) = 'ACTIVE'
 AND a.applied_to_entry_id IS DISTINCT FROM p.survivor_id
JOIN customer_account_entry ch
  ON ch.id = a.applied_to_entry_id
 AND upper(ch.entry_type) = 'CHARGE'
 AND ch.production_order_id = p.production_order_id
WHERE p.accion IN ('CONSERVAR', 'NUEVO');

UPDATE customer_account_entry e
SET status = 'VOID',
    void_reason = 'LIMPIEZA-CXC-DUPLICADOS-2026-10',
    voided_at = now(),
    updated_at = now()
FROM plan p
WHERE p.accion IN ('CONSERVAR', 'NUEVO')
  AND e.production_order_id = p.production_order_id
  AND upper(e.entry_type) = 'CHARGE'
  AND upper(coalesce(e.status, '')) = 'ACTIVE'
  AND (p.accion = 'NUEVO' OR e.id IS DISTINCT FROM p.survivor_id);

WITH ins AS (
    INSERT INTO customer_account_entry (
        customer_id, entry_type, entry_date, amount, description,
        production_order_id, order_kind, document_number, invoice_number, vendor_shipment_number,
        status, created_at, updated_at
    )
    SELECT p.customer_id,
           'CHARGE',
           p.oldest_date,
           p.products,
           'LIMPIEZA-CXC-CARGO-NUEVO-2026-10',
           p.production_order_id,
           p.order_kind,
           p.document_number,
           p.invoice_number,
           p.vendor_shipment_number,
           'ACTIVE',
           now(),
           now()
    FROM plan p
    WHERE p.accion = 'NUEVO'
    RETURNING id, production_order_id
)
UPDATE plan p
SET survivor_id = ins.id
FROM ins
WHERE p.production_order_id = ins.production_order_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM plan
        WHERE accion IN ('CONSERVAR', 'NUEVO') AND survivor_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Limpieza sin cargo sobreviviente.';
    END IF;
END $$;

UPDATE customer_account_entry c
SET applied_to_entry_id = p.survivor_id,
    production_order_id = p.production_order_id,
    order_kind = p.order_kind,
    reassigned_from_entry_id = COALESCE(c.reassigned_from_entry_id, m.from_charge_id),
    updated_at = now()
FROM creditos_movidos m
JOIN plan p ON p.production_order_id = m.production_order_id
WHERE c.id = m.entry_id;

-- Only the charge link changes. entry_date stays, so the read-time due date stays.
-- reassigned_from_entry_id keeps the first original charge. The marker records this
-- re-link (old charge id) for rollback; it is not the new-adjustment description tag,
-- so reconcile does not treat the existing amount as a new debit.
UPDATE customer_account_entry a
SET applied_to_entry_id = p.survivor_id,
    production_order_id = p.production_order_id,
    order_kind = p.order_kind,
    reassigned_from_entry_id = COALESCE(a.reassigned_from_entry_id, r.from_charge_id),
    description = CASE
        WHEN coalesce(a.description, '') ~ ('LIMPIEZA-CXC-2026-10 cargo:' || r.from_charge_id::text || '(\D|$)')
            THEN a.description
        WHEN a.description IS NULL OR btrim(a.description) = ''
            THEN 'LIMPIEZA-CXC-2026-10 cargo:' || r.from_charge_id::text
        ELSE a.description || E'\n' || 'LIMPIEZA-CXC-2026-10 cargo:' || r.from_charge_id::text
    END,
    updated_at = now()
FROM ajustes_reapuntados r
JOIN plan p ON p.production_order_id = r.production_order_id
WHERE a.id = r.entry_id;

WITH ins AS (
    INSERT INTO customer_account_entry (
        customer_id, entry_type, entry_date, amount, description,
        production_order_id, product_shipment_id, applied_to_entry_id, partial_release_id,
        order_kind, document_number, invoice_number, vendor_shipment_number,
        status, created_at, updated_at
    )
    SELECT p.customer_id,
           'CHARGE_ADJUSTMENT',
           COALESCE(ps.sent_at::date, p.oldest_date, CURRENT_DATE),
           round(ps.shipping_cost, 2),
           'LIMPIEZA-CXC-AJUSTE-ENVIO-2026-10',
           p.production_order_id,
           ps.id,
           p.survivor_id,
           CASE WHEN EXISTS (
               SELECT 1 FROM production_order_partial_release r WHERE r.id = ps.partial_release_id
           ) THEN ps.partial_release_id END,
           p.order_kind,
           p.document_number,
           COALESCE((SELECT o.invoice_number FROM customer_account_entry o
                      WHERE o.product_shipment_id = ps.id
                        AND upper(o.entry_type) = 'CHARGE'
                        AND o.invoice_number IS NOT NULL
                      ORDER BY o.entry_date, o.id
                      LIMIT 1), p.invoice_number),
           p.vendor_shipment_number,
           'ACTIVE',
           now(),
           now()
    FROM plan p
    JOIN product_shipment ps ON ps.production_order_id = p.production_order_id
    WHERE p.accion IN ('CONSERVAR', 'NUEVO')
      AND ps.shipping_cost > 0
      AND NOT EXISTS (
          SELECT 1
          FROM customer_account_entry a
          WHERE a.product_shipment_id = ps.id
            AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
            AND upper(coalesce(a.status, '')) <> 'VOID'
      )
    RETURNING id, production_order_id, product_shipment_id, amount
)
INSERT INTO ajustes_nuevos (entry_id, production_order_id, shipment_id, amount)
SELECT id, production_order_id, product_shipment_id, amount FROM ins;

CREATE TEMP TABLE saldo_despues ON COMMIT DROP AS
SELECT customer_id,
       round(COALESCE(sum(
           CASE
               WHEN upper(coalesce(status, '')) <> 'ACTIVE' THEN 0
               WHEN upper(entry_type) IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT') THEN COALESCE(amount, 0)
               WHEN upper(entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN
                   -1 * CASE
                       WHEN gross_collected_amount > 0 THEN gross_collected_amount
                       WHEN payment_discount_amount > 0 THEN COALESCE(amount, 0) + payment_discount_amount
                       ELSE COALESCE(amount, 0)
                   END
               ELSE 0
           END
       ), 0), 2) AS saldo
FROM customer_account_entry
GROUP BY customer_id;

SELECT 'SIN_ORDEN' AS seccion, e.id AS entry_id, e.customer_id, e.amount, e.entry_date
FROM customer_account_entry e
WHERE upper(e.entry_type) = 'CHARGE'
  AND upper(coalesce(e.status, '')) = 'ACTIVE'
  AND e.production_order_id IS NULL
ORDER BY e.id;

SELECT 'PLAN' AS seccion,
       p.accion,
       p.production_order_id,
       p.customer_id,
       p.products,
       p.shipping,
       p.charge_sum AS suma_cargos,
       p.oldest_id AS cargo_antiguo_id,
       p.oldest_amount AS cargo_antiguo_monto,
       p.survivor_id,
       sv.amount AS survivor_monto,
       (SELECT string_agg(v.id::text, ',' ORDER BY v.id)
          FROM customer_account_entry v
         WHERE v.production_order_id = p.production_order_id
           AND upper(v.entry_type) = 'CHARGE'
           AND upper(v.status) = 'VOID'
           AND v.void_reason = 'LIMPIEZA-CXC-DUPLICADOS-2026-10') AS cargos_anulados,
       (SELECT string_agg(m.entry_id::text, ',' ORDER BY m.entry_id)
          FROM creditos_movidos m
         WHERE m.production_order_id = p.production_order_id) AS creditos_movidos,
       (SELECT string_agg(r.entry_id::text, ',' ORDER BY r.entry_id)
          FROM ajustes_reapuntados r
         WHERE r.production_order_id = p.production_order_id) AS ajustes_reapuntados,
       (SELECT string_agg(n.shipment_id::text || '=' || n.amount::text, ',' ORDER BY n.shipment_id)
          FROM ajustes_nuevos n
         WHERE n.production_order_id = p.production_order_id) AS ajustes_nuevos,
       sa.saldo AS saldo_antes,
       sd.saldo AS saldo_despues
FROM plan p
JOIN customer_account_entry sv ON sv.id = p.survivor_id
LEFT JOIN saldo_antes sa ON sa.customer_id = p.customer_id
LEFT JOIN saldo_despues sd ON sd.customer_id = p.customer_id
WHERE p.accion IN ('CONSERVAR', 'NUEVO')
ORDER BY p.production_order_id;

SELECT 'REENLACE' AS seccion,
       r.entry_id AS ajuste_id,
       r.from_charge_id AS cargo_anterior,
       p.survivor_id AS cargo_nuevo,
       'LIMPIEZA-CXC-2026-10' AS quien,
       transaction_timestamp() AS cuando,
       'reenlace de ajuste al cargo sobreviviente' AS por_que
FROM ajustes_reapuntados r
JOIN plan p ON p.production_order_id = r.production_order_id
ORDER BY r.entry_id;

SELECT 'REVIEW' AS seccion,
       'estado después de aplicar' AS estado,
       production_order_id,
       customer_id,
       (charge_sum + adj_sum) AS debito_antes,
       (products + adj_sum + missing_shipping) AS debito_despues,
       ((products + missing_shipping) - charge_sum) AS delta,
       motivo
FROM plan
WHERE accion = 'REVIEW'
ORDER BY production_order_id;

WITH uno AS (
    SELECT production_order_id, min(id) AS charge_id, min(customer_id) AS customer_id
    FROM customer_account_entry
    WHERE upper(entry_type) = 'CHARGE'
      AND upper(coalesce(status, '')) = 'ACTIVE'
      AND production_order_id IS NOT NULL
    GROUP BY production_order_id
    HAVING count(*) = 1
)
SELECT 'OVERPAID' AS seccion,
       'estado después de aplicar' AS estado,
       u.production_order_id,
       u.customer_id,
       ch.amount AS cargo,
       COALESCE(adj.total, 0) AS ajustes,
       COALESCE(cred.total, 0) AS creditos,
       round(COALESCE(cred.total, 0) - (ch.amount + COALESCE(adj.total, 0)), 2) AS exceso
FROM uno u
JOIN customer_account_entry ch ON ch.id = u.charge_id
LEFT JOIN LATERAL (
    SELECT sum(a.amount) AS total
    FROM customer_account_entry a
    WHERE a.applied_to_entry_id = u.charge_id
      AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
      AND upper(coalesce(a.status, '')) = 'ACTIVE'
) adj ON true
LEFT JOIN LATERAL (
    SELECT sum(CASE
                   WHEN c.gross_collected_amount > 0 THEN c.gross_collected_amount
                   WHEN c.payment_discount_amount > 0 THEN COALESCE(c.amount, 0) + c.payment_discount_amount
                   ELSE COALESCE(c.amount, 0)
               END) AS total
    FROM customer_account_entry c
    WHERE c.applied_to_entry_id = u.charge_id
      AND upper(c.entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN')
      AND upper(coalesce(c.status, '')) = 'ACTIVE'
) cred ON true
WHERE COALESCE(cred.total, 0) > ch.amount + COALESCE(adj.total, 0)
ORDER BY u.production_order_id;

SELECT 'RESUMEN' AS seccion,
       (SELECT count(*) FROM plan WHERE accion IN ('CONSERVAR', 'NUEVO')) AS a_corregir,
       (SELECT count(*) FROM plan WHERE accion = 'REVIEW') AS en_revision,
       (SELECT count(*)
          FROM (
              SELECT production_order_id, min(id) AS charge_id
              FROM customer_account_entry
              WHERE upper(entry_type) = 'CHARGE'
                AND upper(coalesce(status, '')) = 'ACTIVE'
                AND production_order_id IS NOT NULL
              GROUP BY production_order_id
              HAVING count(*) = 1
          ) u
          JOIN customer_account_entry ch ON ch.id = u.charge_id
         WHERE COALESCE((
               SELECT sum(CASE
                              WHEN c.gross_collected_amount > 0 THEN c.gross_collected_amount
                              WHEN c.payment_discount_amount > 0 THEN COALESCE(c.amount, 0) + c.payment_discount_amount
                              ELSE COALESCE(c.amount, 0)
                          END)
               FROM customer_account_entry c
               WHERE c.applied_to_entry_id = u.charge_id
                 AND upper(c.entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN')
                 AND upper(coalesce(c.status, '')) = 'ACTIVE'
           ), 0) > ch.amount + COALESCE((
               SELECT sum(a.amount)
               FROM customer_account_entry a
               WHERE a.applied_to_entry_id = u.charge_id
                 AND upper(a.entry_type) = 'CHARGE_ADJUSTMENT'
                 AND upper(coalesce(a.status, '')) = 'ACTIVE'
           ), 0)
       ) AS sobrepago,
       CASE WHEN :'aplicar' = 'si' THEN 'COMMIT' ELSE 'ROLLBACK' END AS cierre;

-- Same predicates as migration-customer-account-lf-phase2.sql. Printed after the writes and before
-- COMMIT/ROLLBACK, so a dry run shows what would still block phase 2 if this run were applied.
SELECT 'PHASE2_BLOCKERS' AS seccion,
       dup.ordenes,
       sin.cargos AS cargos_sin_orden,
       CASE
           WHEN dup.ordenes = 0 AND sin.cargos = 0 THEN
               'Fase 2: ningun bloqueo. 0 ordenes con cargos duplicados y 0 cargos activos sin orden.'
           ELSE format(
               'Fase 2 sigue bloqueada: %s orden(es) con mas de un cargo activo y %s cargo(s) activo(s) sin orden.',
               dup.ordenes, sin.cargos)
       END AS detalle
FROM (
    SELECT count(*) AS ordenes
    FROM (
        SELECT production_order_id
        FROM customer_account_entry
        WHERE entry_type = 'CHARGE'
          AND status <> 'VOID'
          AND production_order_id IS NOT NULL
        GROUP BY production_order_id
        HAVING count(*) > 1
    ) d
) dup
CROSS JOIN (
    SELECT count(*) AS cargos
    FROM customer_account_entry
    WHERE entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
      AND status <> 'VOID'
      AND production_order_id IS NULL
) sin;

SELECT 'PHASE2_BLOCKERS_ORDEN' AS seccion,
       production_order_id,
       string_agg(DISTINCT customer_id::text, ',' ORDER BY customer_id::text) AS customer_id,
       count(*) AS cargos,
       string_agg(id::text, ',' ORDER BY id) AS entry_ids
FROM customer_account_entry
WHERE entry_type = 'CHARGE'
  AND status <> 'VOID'
  AND production_order_id IS NOT NULL
GROUP BY production_order_id
HAVING count(*) > 1
ORDER BY production_order_id;

SELECT 'PHASE2_BLOCKERS_SIN_ORDEN' AS seccion,
       id AS entry_id,
       customer_id,
       entry_type
FROM customer_account_entry
WHERE entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
  AND status <> 'VOID'
  AND production_order_id IS NULL
ORDER BY id;

-- Independent of the cleanup tags: every customer's ledger balance, before vs after.
SELECT 'BALANCE_CHECK' AS seccion,
       count(*) AS clientes,
       COALESCE(string_agg(format('cliente %s: %s -> %s (delta %s)',
                                  COALESCE(a.customer_id, d.customer_id),
                                  COALESCE(a.saldo, 0),
                                  COALESCE(d.saldo, 0),
                                  COALESCE(d.saldo, 0) - COALESCE(a.saldo, 0)),
                           '; ' ORDER BY COALESCE(a.customer_id, d.customer_id)),
                'saldos sin cambio') AS detalle
FROM saldo_antes a
FULL JOIN saldo_despues d ON d.customer_id = a.customer_id
WHERE COALESCE(a.saldo, 0) <> COALESCE(d.saldo, 0);

DO $$
DECLARE
    moved text;
BEGIN
    SELECT string_agg(format('cliente %s: %s -> %s (delta %s)',
                             COALESCE(a.customer_id, d.customer_id),
                             COALESCE(a.saldo, 0),
                             COALESCE(d.saldo, 0),
                             COALESCE(d.saldo, 0) - COALESCE(a.saldo, 0)),
                      '; ' ORDER BY COALESCE(a.customer_id, d.customer_id))
      INTO moved
      FROM saldo_antes a
      FULL JOIN saldo_despues d ON d.customer_id = a.customer_id
     WHERE COALESCE(a.saldo, 0) <> COALESCE(d.saldo, 0);
    IF moved IS NOT NULL THEN
        RAISE EXCEPTION 'Limpieza abortada: cambiaria saldos (%). No se cambio nada.', moved;
    END IF;
END $$;

\if :limpieza_commit
COMMIT;
\else
ROLLBACK;
\endif
