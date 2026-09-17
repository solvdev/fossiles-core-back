-- Invierte scripts/backfill-exchange-difference-given-as-venta.sql
-- y deja ambas patas de la boleta como CAMBIO para el conteo fisico:
--   ingreso  (stock_after > stock_before) -> Comp. y Ent.
--   egreso   (stock_after < stock_before) -> Vtas. y Sal.
--
-- El script viejo SOLO paso egresos CAMBIO/DEVOLUCION_A_CLIENTE a VENTA.
-- Este script:
--   1) verifica ingresos (compra)
--   2) pasa egresos VENTA y DEVOLUCION_A_CLIENTE -> CAMBIO
--   3) religa return/given_movement_id si el movimiento existe por numero de boleta
-- No cambia cantidades ni stock.
--
-- kiosco_movement es append-only: el UPDATE requiere el flag de sesion.
-- Ejecutar BEGIN + set_config + SELECT/UPDATE + COMMIT en UN SOLO script.

BEGIN;

SELECT set_config('app.kiosco_movement_admin_mutation', 'true', true);

-- Ingreso mal tipado -> CAMBIO
UPDATE kiosco_movement m
SET movement_type = 'CAMBIO'
FROM kiosk_exchange_slip s
WHERE m.id = s.return_movement_id
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.stock_after > m.stock_before
  AND m.movement_type <> 'CAMBIO';

-- Egreso de boleta (VENTA o legado DEVOLUCION_A_CLIENTE) -> CAMBIO
UPDATE kiosco_movement m
SET movement_type = 'CAMBIO'
FROM kiosk_exchange_slip s
WHERE m.id = s.given_movement_id
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.movement_type IN ('VENTA', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;

UPDATE kiosco_movement m
SET movement_type = 'CAMBIO'
FROM kiosk_exchange_slip_given_item gi
JOIN kiosk_exchange_slip s ON s.id = gi.exchange_slip_id
WHERE m.id = gi.given_movement_id
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.movement_type IN ('VENTA', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;

UPDATE kiosco_movement m
SET movement_type = 'CAMBIO'
FROM kiosco_stock st, kiosk_exchange_slip s
WHERE m.kiosco_stock_id = st.id
  AND s.kiosk_location_id = st.location_id
  AND s.slip_number = m.physical_slip_number
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.movement_type IN ('VENTA', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;

-- Religa IDs cuando el movimiento existe por numero de boleta
UPDATE kiosk_exchange_slip s
SET return_movement_id = m.id
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
WHERE s.return_movement_id IS NULL
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.physical_slip_number = s.slip_number
  AND st.location_id = s.kiosk_location_id
  AND m.stock_after > m.stock_before
  AND m.movement_type = 'CAMBIO';

UPDATE kiosk_exchange_slip s
SET given_movement_id = m.id
FROM kiosco_movement m
JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
WHERE s.given_movement_id IS NULL
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND m.physical_slip_number = s.slip_number
  AND st.location_id = s.kiosk_location_id
  AND m.stock_after < m.stock_before
  AND m.movement_type = 'CAMBIO';

-- Verificacion: boletas cuyo ledger ligado (o por numero) no quedo CAMBIO+/CAMBIO-
-- Las que salgan con "SIN LEDGER" no tienen movimientos: no bloquean el COMMIT del tipo.
SELECT
    s.slip_number,
    COALESCE(ret.movement_type, ret_slip.movement_type) AS ingreso_tipo,
    COALESCE(giv.movement_type, giv_slip.movement_type) AS egreso_tipo,
    CASE
        WHEN COALESCE(ret.id, ret_slip.id) IS NULL THEN 'SIN LEDGER'
        WHEN COALESCE(ret.movement_type, ret_slip.movement_type) = 'CAMBIO'
             AND COALESCE(ret.stock_after, ret_slip.stock_after)
               > COALESCE(ret.stock_before, ret_slip.stock_before) THEN 'OK'
        ELSE 'INGRESO NO ES COMPRA'
    END AS compra,
    CASE
        WHEN COALESCE(giv.id, giv_slip.id) IS NULL THEN 'SIN LEDGER'
        WHEN COALESCE(giv.movement_type, giv_slip.movement_type) = 'CAMBIO'
             AND COALESCE(giv.stock_after, giv_slip.stock_after)
               < COALESCE(giv.stock_before, giv_slip.stock_before) THEN 'OK'
        ELSE 'EGRESO NO ES SALIDA'
    END AS salida
FROM kiosk_exchange_slip s
LEFT JOIN kiosco_movement ret ON ret.id = s.return_movement_id
LEFT JOIN kiosco_movement giv ON giv.id = s.given_movement_id
LEFT JOIN LATERAL (
    SELECT m.*
    FROM kiosco_movement m
    JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
    WHERE m.physical_slip_number = s.slip_number
      AND st.location_id = s.kiosk_location_id
      AND m.stock_after > m.stock_before
    ORDER BY m.id
    LIMIT 1
) ret_slip ON s.return_movement_id IS NULL
LEFT JOIN LATERAL (
    SELECT m.*
    FROM kiosco_movement m
    JOIN kiosco_stock st ON st.id = m.kiosco_stock_id
    WHERE m.physical_slip_number = s.slip_number
      AND st.location_id = s.kiosk_location_id
      AND m.stock_after < m.stock_before
    ORDER BY m.id
    LIMIT 1
) giv_slip ON s.given_movement_id IS NULL
WHERE UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND (
        COALESCE(ret.id, ret_slip.id) IS NULL
        OR COALESCE(giv.id, giv_slip.id) IS NULL
        OR COALESCE(ret.movement_type, ret_slip.movement_type) <> 'CAMBIO'
        OR COALESCE(giv.movement_type, giv_slip.movement_type) <> 'CAMBIO'
      )
ORDER BY s.slip_number;

SELECT set_config('app.kiosco_movement_admin_mutation', 'false', true);

-- Si las unicas filas son SIN LEDGER (boletas sin movimientos), el tipo ya quedo bien:
--   COMMIT;
-- Si aparece INGRESO NO ES COMPRA o EGRESO NO ES SALIDA:
--   ROLLBACK;
