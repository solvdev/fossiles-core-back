-- Diagnóstico: cinchos FOSS donde sizes_data no cuadra con replay de movimientos.
-- Nota: el reporte de conteo físico ya calcula Inv. inicial / Inv. final por talla desde
-- movimientos (Ini + columnas del periodo = Fin). Este script sirve para detectar desfase
-- entre sizes_data (stock vivo) y la cadena de kiosco_movement.
-- Parámetros (ajustar):
--   :location_id  → id del kiosko
--   :period_from   → inicio del conteo, ej. '2026-01-01'
--   :period_to     → fin del conteo, ej. '2026-06-30'

-- ─── 1) Resumen por producto / color / talla (equivalente a fila expandida del Excel) ───
WITH params AS (
    SELECT
        1::bigint AS location_id,          -- ← cambiar
        DATE '2026-01-01' AS period_from,  -- ← cambiar
        DATE '2026-06-30' AS period_to     -- ← cambiar
),
movement_delta AS (
    SELECT
        km.kiosco_stock_id,
        COALESCE(NULLIF(BTRIM(km.size_key), ''), '') AS size_key,
        km.movement_type,
        km.quantity,
        km.stock_before,
        km.stock_after,
        km.affects_stock,
        km.created_at,
        km.id,
        CASE km.movement_type
            WHEN 'ENTRADA'             THEN km.quantity
            WHEN 'TRASLADO_ENTRADA'    THEN km.quantity
            WHEN 'DEVOLUCION_CLIENTE'  THEN km.quantity
            WHEN 'ANULACION'           THEN km.quantity
            WHEN 'VENTA'               THEN -km.quantity
            WHEN 'DEVOLUCION_DEPOSITO' THEN -km.quantity
            WHEN 'TRASLADO_SALIDA'     THEN -km.quantity
            WHEN 'MERMA'               THEN -km.quantity
            WHEN 'AJUSTE'              THEN km.stock_after - km.stock_before
            WHEN 'CAMBIO'              THEN km.stock_after - km.stock_before
            ELSE 0
        END AS signed_delta
    FROM kiosco_movement km
    JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
    CROSS JOIN params p
    WHERE ks.location_id = p.location_id
),
-- Saldo replay por talla al inicio del periodo (antes de period_from)
balance_before AS (
    SELECT
        md.kiosco_stock_id,
        md.size_key,
        SUM(md.signed_delta) FILTER (WHERE md.affects_stock = TRUE) AS balance
    FROM movement_delta md
    CROSS JOIN params p
    WHERE md.created_at < p.period_from::timestamp
    GROUP BY md.kiosco_stock_id, md.size_key
),
-- Kardex del periodo por talla (misma lógica que el backend)
period_kardex AS (
    SELECT
        md.kiosco_stock_id,
        md.size_key,
        SUM(CASE WHEN md.affects_stock AND md.movement_type IN ('ENTRADA', 'TRASLADO_ENTRADA') AND md.signed_delta > 0
                 THEN md.signed_delta ELSE 0 END) AS entradas,
        SUM(CASE WHEN md.affects_stock AND md.movement_type IN ('AJUSTE', 'DEVOLUCION_CLIENTE', 'CAMBIO') AND md.signed_delta > 0
                 THEN md.signed_delta ELSE 0 END) AS compras_ajustes,
        SUM(CASE WHEN md.affects_stock AND md.movement_type IN ('AJUSTE', 'CAMBIO') AND md.signed_delta < 0
                 THEN -md.signed_delta ELSE 0 END) AS anulacion_compras,
        SUM(CASE WHEN md.affects_stock AND md.movement_type = 'VENTA' AND md.signed_delta < 0
                 THEN -md.signed_delta ELSE 0 END) AS ventas,
        SUM(CASE WHEN md.affects_stock AND md.movement_type = 'ANULACION' AND md.signed_delta > 0
                 THEN md.signed_delta ELSE 0 END) AS anulacion_venta,
        SUM(CASE WHEN md.affects_stock AND md.movement_type IN ('DEVOLUCION_DEPOSITO', 'TRASLADO_SALIDA', 'MERMA', 'CAMBIO')
                      AND md.signed_delta < 0
                 THEN -md.signed_delta ELSE 0 END) AS salida,
        SUM(md.signed_delta) FILTER (WHERE md.affects_stock = TRUE) AS neto_periodo
    FROM movement_delta md
    CROSS JOIN params p
    WHERE md.created_at >= p.period_from::timestamp
      AND md.created_at < (p.period_to + INTERVAL '1 day')::timestamp
    GROUP BY md.kiosco_stock_id, md.size_key
),
-- Saldo replay al cierre del periodo por talla
balance_after AS (
    SELECT
        md.kiosco_stock_id,
        md.size_key,
        SUM(md.signed_delta) FILTER (WHERE md.affects_stock = TRUE) AS balance
    FROM movement_delta md
    CROSS JOIN params p
    WHERE md.created_at < (p.period_to + INTERVAL '1 day')::timestamp
    GROUP BY md.kiosco_stock_id, md.size_key
),
-- sizes_data desglosado (stock vivo por talla)
stock_sizes AS (
    SELECT
        ks.id AS kiosco_stock_id,
        e.key AS size_key,
        GREATEST(0, (e.value::text)::numeric::int) AS sizes_data_qty
    FROM kiosco_stock ks
    CROSS JOIN LATERAL jsonb_each_text(
        CASE
            WHEN ks.sizes_data IS NOT NULL AND BTRIM(ks.sizes_data) <> ''
            THEN ks.sizes_data::jsonb
            ELSE '{}'::jsonb
        END
    ) AS e(key, value)
    CROSS JOIN params p
    WHERE ks.location_id = p.location_id
),
all_size_keys AS (
    SELECT kiosco_stock_id, size_key FROM balance_before
    UNION
    SELECT kiosco_stock_id, size_key FROM period_kardex
    UNION
    SELECT kiosco_stock_id, size_key FROM balance_after
    UNION
    SELECT kiosco_stock_id, size_key FROM stock_sizes
)
SELECT
    p.code AS producto_codigo,
    p.name AS producto,
    c.name AS color,
    ask.size_key AS talla,
    GREATEST(0, COALESCE(bb.balance, 0)) AS inv_inicial_replay,
    COALESCE(pk.compras_ajustes, 0) AS compras_ajustes,
    COALESCE(pk.anulacion_compras, 0) AS anul_compras,
    COALESCE(pk.entradas, 0) AS entradas,
    COALESCE(pk.ventas, 0) AS ventas,
    COALESCE(pk.anulacion_venta, 0) AS anul_venta,
    COALESCE(pk.salida, 0) AS salidas,
    GREATEST(0, COALESCE(ba.balance, 0)) AS inv_final_replay,
    COALESCE(ss.sizes_data_qty, 0) AS inv_final_sizes_data,
    -- Lo que el Excel muestra hoy como Inv. final (sizes_data; 0 si la talla no está en JSON)
    COALESCE(ss.sizes_data_qty, 0) AS inv_final_reporte,
    -- ¿Cuadra Ini + movimientos = Fin (replay)?
    GREATEST(0, COALESCE(bb.balance, 0))
        + COALESCE(pk.compras_ajustes, 0)
        - COALESCE(pk.anulacion_compras, 0)
        + COALESCE(pk.entradas, 0)
        - COALESCE(pk.ventas, 0)
        + COALESCE(pk.anulacion_venta, 0)
        - COALESCE(pk.salida, 0) AS inv_final_calculado,
    COALESCE(ss.sizes_data_qty, 0)
        - GREATEST(0, COALESCE(ba.balance, 0)) AS desync_sizes_vs_replay
FROM all_size_keys ask
JOIN kiosco_stock ks ON ks.id = ask.kiosco_stock_id
JOIN product p ON p.id = ks.product_id
LEFT JOIN colors c ON c.id = ks.color_id
LEFT JOIN balance_before bb
       ON bb.kiosco_stock_id = ask.kiosco_stock_id AND bb.size_key = ask.size_key
LEFT JOIN period_kardex pk
       ON pk.kiosco_stock_id = ask.kiosco_stock_id AND pk.size_key = ask.size_key
LEFT JOIN balance_after ba
       ON ba.kiosco_stock_id = ask.kiosco_stock_id AND ba.size_key = ask.size_key
LEFT JOIN stock_sizes ss
       ON ss.kiosco_stock_id = ask.kiosco_stock_id AND ss.size_key = ask.size_key
CROSS JOIN params par
WHERE ks.location_id = par.location_id
  AND ask.size_key <> ''   -- excluir bucket sin talla (agregado)
  AND (
      -- Caso amarillo: entrada en periodo, sin salidas, stock sizes_data = 0
      (COALESCE(pk.entradas, 0) > 0
       AND COALESCE(pk.ventas, 0) = 0
       AND COALESCE(pk.salida, 0) = 0
       AND COALESCE(ss.sizes_data_qty, 0) = 0)
      OR
      -- sizes_data distinto al replay de movimientos
      (COALESCE(ss.sizes_data_qty, 0) <> GREATEST(0, COALESCE(ba.balance, 0)))
      OR
      -- Inv. inicial del reporte (back-calculado) no cuadra con replay real
      (GREATEST(0, COALESCE(bb.balance, 0))
       + COALESCE(pk.compras_ajustes, 0) - COALESCE(pk.anulacion_compras, 0)
       + COALESCE(pk.entradas, 0) - COALESCE(pk.ventas, 0)
       + COALESCE(pk.anulacion_venta, 0) - COALESCE(pk.salida, 0))
       <> COALESCE(ss.sizes_data_qty, 0)
  )
ORDER BY p.code, c.name NULLS FIRST, ask.size_key;


-- ─── 2) Movimientos ENTRADA sospechosos (detalle para investigar) ───
-- Ejecutar aparte reemplazando los mismos parámetros en el WHERE.

/*
SELECT
    km.id,
    p.code,
    c.name AS color,
    km.size_key AS talla,
    km.quantity,
    km.stock_before,
    km.stock_after,
    km.affects_stock,
    km.reference_id,
    km.reason,
    km.created_at
FROM kiosco_movement km
JOIN kiosco_stock ks ON ks.id = km.kiosco_stock_id
JOIN product p ON p.id = ks.product_id
LEFT JOIN colors c ON c.id = ks.color_id
WHERE ks.location_id = 1
  AND km.movement_type IN ('ENTRADA', 'TRASLADO_ENTRADA')
  AND km.created_at >= TIMESTAMP '2026-01-01'
  AND km.created_at < TIMESTAMP '2026-07-01'
ORDER BY p.code, c.name, km.size_key, km.created_at;
*/
