-- SNAPSHOT of receivable balances per customer (READ ONLY). Index version for PR #120 / the duplicate-charge cleanup.
-- Server rule (CustomerAccountService on #120): only status ACTIVE counts;
--   debit  = amount for CHARGE, OPENING_BALANCE, CHARGE_ADJUSTMENT
--   credit = gross_collected_amount if > 0, else amount + payment_discount_amount if > 0, else amount (PAYMENT, CREDIT_NOTE, RETURN)
-- OPV/OPC split as computeKindSplit (kind re-derived from production_order; credits only when applied_to_entry_id is set).
-- Optional cleanup tags (fixed strings the cleanup script writes):
--   -v void_tag='<void_reason written on the charges the cleanup voids>'
--   -v adj_tag='<description written on the CHARGE_ADJUSTMENT rows the cleanup creates>'
--   -v charge_tag='<description written on any NEW order CHARGE the cleanup creates (if it creates instead of keeping one)>'
-- No names, NIT, phones or addresses.
-- Usage: psql -d fosstest -v out=cxc-fosstest-antes.csv -v void_tag='...' -v adj_tag='...' -v charge_tag='...' -f snapshot-saldos-cxc.sql
\set ON_ERROR_STOP on
\if :{?out}
\else
  \set out cxc-snapshot.csv
\endif
\if :{?void_tag}
\else
  \set void_tag ''
\endif
\if :{?adj_tag}
\else
  \set adj_tag ''
\endif
\if :{?charge_tag}
\else
  \set charge_tag ''
\endif

SET default_transaction_read_only = on;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT current_database() AS base, current_schema() AS esquema, now() AS foto_en;

SELECT upper(entry_type) AS entry_type, COALESCE(upper(status), '(null)') AS status,
       count(*) AS movimientos, sum(amount) AS suma_amount
FROM customer_account_entry
GROUP BY 1, 2
ORDER BY 1, 2;

\pset format csv
\pset footer off
\o :out
WITH e AS (
    SELECT cae.customer_id,
           cae.applied_to_entry_id,
           upper(cae.entry_type) AS tipo,
           upper(COALESCE(cae.status, '')) = 'ACTIVE' AS activo,
           (upper(cae.entry_type) = 'CHARGE' AND upper(COALESCE(cae.status, '')) = 'VOID'
                AND cae.void_reason = :'void_tag') AS anulado_por_limpieza,
           (upper(cae.entry_type) = 'CHARGE_ADJUSTMENT' AND upper(COALESCE(cae.status, '')) = 'ACTIVE'
                AND cae.description = :'adj_tag') AS ajuste_por_limpieza,
           (upper(cae.entry_type) = 'CHARGE' AND upper(COALESCE(cae.status, '')) = 'ACTIVE'
                AND cae.description = :'charge_tag') AS cargo_nuevo_por_limpieza,
           COALESCE(cae.amount, 0) AS amount,
           CASE
               WHEN cae.production_order_id IS NOT NULL AND po.id IS NOT NULL THEN
                   CASE
                       WHEN upper(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
                        AND upper(trim(COALESCE(po.order_type, ''))) NOT IN ('INTERNA', 'CLIENTE_KIOSKO') THEN
                           CASE WHEN upper(trim(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS', 'MARCAS')
                                THEN 'OPC' ELSE 'OPV' END
                       ELSE COALESCE(NULLIF(upper(trim(po.order_type)), ''), 'OP')
                   END
               ELSE upper(cae.order_kind)
           END AS kind,
           CASE WHEN upper(cae.entry_type) IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT')
                THEN COALESCE(cae.amount, 0) ELSE 0 END AS debit,
           CASE WHEN upper(cae.entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN
                    round(CASE
                              WHEN cae.gross_collected_amount > 0 THEN cae.gross_collected_amount
                              WHEN cae.payment_discount_amount > 0 THEN COALESCE(cae.amount, 0) + cae.payment_discount_amount
                              ELSE COALESCE(cae.amount, 0)
                          END, 2)
                ELSE 0 END AS credit
    FROM customer_account_entry cae
    LEFT JOIN production_order po ON po.id = cae.production_order_id
)
SELECT e.customer_id,
       c.legacy_code,
       count(*) FILTER (WHERE e.activo) AS movimientos_activos,
       COALESCE(sum(e.debit) FILTER (WHERE e.activo), 0) AS cargos,
       COALESCE(sum(e.credit) FILTER (WHERE e.activo AND e.tipo = 'PAYMENT'), 0) AS abonos,
       COALESCE(sum(e.credit) FILTER (WHERE e.activo AND e.tipo = 'CREDIT_NOTE'), 0) AS notas_credito,
       COALESCE(sum(e.credit) FILTER (WHERE e.activo AND e.tipo = 'RETURN'), 0) AS devoluciones,
       round(COALESCE(sum(e.debit - e.credit) FILTER (WHERE e.activo), 0), 2) AS saldo,
       round(COALESCE(sum(e.debit) FILTER (WHERE e.activo AND e.kind = 'OPV'), 0)
             - COALESCE(sum(e.credit) FILTER (WHERE e.activo AND e.kind = 'OPV' AND e.applied_to_entry_id IS NOT NULL), 0), 2) AS neto_opv,
       round(COALESCE(sum(e.debit) FILTER (WHERE e.activo AND e.kind = 'OPC'), 0)
             - COALESCE(sum(e.credit) FILTER (WHERE e.activo AND e.kind = 'OPC' AND e.applied_to_entry_id IS NOT NULL), 0), 2) AS neto_opc,
       COALESCE(sum(e.amount) FILTER (WHERE e.anulado_por_limpieza), 0) AS anulado_por_limpieza,
       COALESCE(sum(e.amount) FILTER (WHERE e.ajuste_por_limpieza), 0) AS ajustes_por_limpieza,
       COALESCE(sum(e.amount) FILTER (WHERE e.cargo_nuevo_por_limpieza), 0) AS cargos_nuevos_por_limpieza
FROM e
LEFT JOIN customer c ON c.id = e.customer_id
GROUP BY e.customer_id, c.legacy_code
ORDER BY e.customer_id;
\o
\pset format aligned

COMMIT;
