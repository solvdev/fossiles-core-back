-- Ventas POS con sale_date de un día anterior al real.
-- Causa: el POS calculaba "hoy" al abrir la pantalla y lo mandaba en cada venta; si la pantalla
-- quedaba abierta de un día para otro, la venta se guardaba con la fecha del día anterior
-- (ej. Interplaza Xela, POS-20261030-0065 cobrada el 31/10 15:23 salía en el reporte del 30).
-- sold_at siempre fue la hora real del servidor (Guatemala), así que es la referencia.
-- Se excluyen las ventas restauradas (sold_at viejo, created_at de hoy) comparando con created_at.
-- El número de venta (POS-AAAAMMDD-...) y la FEL no se tocan.

-- 1) Auditoría
SELECT s.id,
       s.sale_number,
       l.name            AS kiosco,
       s.sale_date,
       s.sold_at,
       s.created_at,
       s.status,
       s.total_amount
FROM kiosk_sale s
LEFT JOIN locations l ON l.id = s.kiosk_location_id
WHERE s.sale_date <> s.sold_at::date
  AND s.created_at IS NOT NULL
  AND ABS(EXTRACT(EPOCH FROM (s.created_at - s.sold_at))) < 3600
ORDER BY s.sold_at DESC;

-- 2) Corrección (revisar la auditoría antes)
BEGIN;

UPDATE kiosk_sale s
SET sale_date = s.sold_at::date
WHERE s.sale_date <> s.sold_at::date
  AND s.created_at IS NOT NULL
  AND ABS(EXTRACT(EPOCH FROM (s.created_at - s.sold_at))) < 3600;

-- COMMIT;  -- o ROLLBACK;
