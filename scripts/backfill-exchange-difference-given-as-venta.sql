-- NO EJECUTAR. Quedó invertido por scripts/backfill-exchange-given-as-cambio.sql
-- (egresos de boleta de cambio deben ser CAMBIO, no VENTA).
--
-- Recategorizaba SOLO egresos de boletas de cambio CON diferencia (difference_amount > 0).
-- Sin diferencia / saldo a favor: se quedan como CAMBIO (Salida).
-- El producto devuelto (CAMBIO +) no se toca.

UPDATE kiosco_movement m
SET movement_type = 'VENTA'
FROM kiosk_exchange_slip s
WHERE m.id = s.given_movement_id
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND s.difference_amount > 0
  AND m.movement_type IN ('CAMBIO', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;

UPDATE kiosco_movement m
SET movement_type = 'VENTA'
FROM kiosk_exchange_slip_given_item gi
JOIN kiosk_exchange_slip s ON s.id = gi.exchange_slip_id
WHERE m.id = gi.given_movement_id
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND s.difference_amount > 0
  AND m.movement_type IN ('CAMBIO', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;

UPDATE kiosco_movement m
SET movement_type = 'VENTA'
FROM kiosco_stock st, kiosk_exchange_slip s
WHERE m.kiosco_stock_id = st.id
  AND s.kiosk_location_id = st.location_id
  AND s.slip_number = m.physical_slip_number
  AND UPPER(s.slip_type) = 'EXCHANGE'
  AND UPPER(s.status) = 'COMPLETED'
  AND s.difference_amount > 0
  AND m.movement_type IN ('CAMBIO', 'DEVOLUCION_A_CLIENTE')
  AND m.stock_after < m.stock_before;
