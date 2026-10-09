-- RECONCILE the duplicate-charge cleanup per customer (agreed check):
--   saldo_despues = saldo_antes - anulado_por_limpieza + ajustes_por_limpieza + cargos_nuevos_por_limpieza
--   (the last term is 0 when the cleanup keeps an existing charge unchanged instead of creating a new one)
-- Inputs: two CSVs written by snapshot-saldos-cxc.sql (this folder) with the SAME -v void_tag / -v adj_tag,
-- the "antes" right before the cleanup and the "despues" right after, with no app traffic in between.
-- Writes nothing to project tables: the CSVs go into TEMP tables of this session only.
-- Usage: copy/rename the two files to cxc-antes.csv and cxc-despues.csv in the current folder, then
--   psql -d fosstest -f reconcile-cleanup-balances.sql
-- Result: 0 rows = every customer reconciles. Any row = stop and investigate before phase 2.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE snap_antes (customer_id bigint, legacy_code text, movimientos_activos bigint, cargos numeric, abonos numeric,
    notas_credito numeric, devoluciones numeric, saldo numeric, neto_opv numeric, neto_opc numeric,
    anulado_por_limpieza numeric, ajustes_por_limpieza numeric, cargos_nuevos_por_limpieza numeric) ON COMMIT DROP;
CREATE TEMP TABLE snap_despues (LIKE snap_antes) ON COMMIT DROP;
\copy snap_antes FROM 'cxc-antes.csv' WITH (FORMAT csv, HEADER)
\copy snap_despues FROM 'cxc-despues.csv' WITH (FORMAT csv, HEADER)

SELECT COALESCE(d.customer_id, a.customer_id) AS customer_id,
       COALESCE(d.legacy_code, a.legacy_code) AS legacy_code,
       COALESCE(a.saldo, 0) AS saldo_antes,
       COALESCE(d.anulado_por_limpieza, 0) - COALESCE(a.anulado_por_limpieza, 0) AS anulado_por_limpieza,
       COALESCE(d.ajustes_por_limpieza, 0) - COALESCE(a.ajustes_por_limpieza, 0) AS ajustes_por_limpieza,
       COALESCE(d.cargos_nuevos_por_limpieza, 0) - COALESCE(a.cargos_nuevos_por_limpieza, 0) AS cargos_nuevos_por_limpieza,
       COALESCE(a.saldo, 0)
           - (COALESCE(d.anulado_por_limpieza, 0) - COALESCE(a.anulado_por_limpieza, 0))
           + (COALESCE(d.ajustes_por_limpieza, 0) - COALESCE(a.ajustes_por_limpieza, 0))
           + (COALESCE(d.cargos_nuevos_por_limpieza, 0) - COALESCE(a.cargos_nuevos_por_limpieza, 0)) AS saldo_esperado,
       COALESCE(d.saldo, 0) AS saldo_despues
FROM snap_despues d
FULL JOIN snap_antes a ON a.customer_id = d.customer_id
WHERE COALESCE(d.saldo, 0) <> COALESCE(a.saldo, 0)
        - (COALESCE(d.anulado_por_limpieza, 0) - COALESCE(a.anulado_por_limpieza, 0))
        + (COALESCE(d.ajustes_por_limpieza, 0) - COALESCE(a.ajustes_por_limpieza, 0))
        + (COALESCE(d.cargos_nuevos_por_limpieza, 0) - COALESCE(a.cargos_nuevos_por_limpieza, 0))
ORDER BY 1;
COMMIT;
