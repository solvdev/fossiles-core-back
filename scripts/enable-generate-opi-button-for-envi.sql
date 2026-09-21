-- Por qué no sale «Generar OPI» y cómo habilitarlo en un ENVI concreto.
-- PostgreSQL. El botón aparece solo si:
--   solicitud APROBADA
--   request_type <> OPI
--   product_shipment_id IS NOT NULL
--   internal_shipment_request.production_order_id IS NULL
--   product_shipment.production_order_id IS NULL
--
-- 1) Cambia el número ENVI (o el id de solicitud) y corre el SELECT.
-- 2) Si el motivo es vínculo de orden, corre el UPDATE de la sección 2.

-- ========== 1) Diagnóstico ==========
WITH target AS (
    SELECT 'ENVI-00000'::text AS shipment_number,   -- <-- CAMBIAR
           NULL::bigint       AS request_id         -- o pon el id de solicitud
)
SELECT
    r.id                         AS request_id,
    r.status,
    r.request_type,
    r.recipient_name,
    r.product_shipment_id,
    r.production_order_id        AS request_opi_id,
    po.code                      AS request_opi_code,
    s.id                         AS shipment_id,
    s.shipment_number,
    s.production_order_id        AS shipment_opi_id,
    spo.code                     AS shipment_opi_code,
    CASE
        WHEN r.id IS NULL THEN
            'No hay solicitud ligada a ese ENVI (product_shipment_id).'
        WHEN UPPER(r.status) <> 'APROBADA' THEN
            'La solicitud no está APROBADA (el botón solo sale en aprobadas).'
        WHEN UPPER(COALESCE(r.request_type, '')) = 'OPI' THEN
            'Es solicitud tipo OPI: el envío nació de una orden, no se genera otra.'
        WHEN r.product_shipment_id IS NULL THEN
            'La solicitud no tiene ENVI ligado (product_shipment_id).'
        WHEN r.production_order_id IS NOT NULL THEN
            'La solicitud ya tiene OPI: ' || COALESCE(po.code, r.production_order_id::text)
        WHEN s.production_order_id IS NOT NULL THEN
            'El ENVI ya está asociado a una orden: ' || COALESCE(spo.code, s.production_order_id::text)
        ELSE
            'Debería mostrar el botón. Recargue la página; no hace falta UPDATE.'
    END AS porque_no_sale
FROM target t
LEFT JOIN product_shipment s
       ON (t.shipment_number IS NOT NULL AND t.shipment_number <> 'ENVI-00000'
           AND UPPER(s.shipment_number) = UPPER(t.shipment_number))
LEFT JOIN internal_shipment_request r
       ON r.id = t.request_id
       OR r.product_shipment_id = s.id
LEFT JOIN production_order po
       ON po.id = r.production_order_id
LEFT JOIN production_order spo
       ON spo.id = s.production_order_id;

-- ========== 2) Habilitar botón (solo si el SELECT dijo que hay OPI/orden ligada) ==========
-- Descomenta UNA de las dos opciones. Reemplaza ENVI-00000.

-- 2a) La solicitud ya tiene OPI y quieres volver a generar otra:
--     (la orden vieja no se borra; solo se desliga de la solicitud)
/*
UPDATE internal_shipment_request r
SET production_order_id = NULL,
    opi_authorized_at = NULL,
    opi_authorized_by = NULL,
    updated_at = NOW()
FROM product_shipment s
WHERE s.shipment_number = 'ENVI-00000'
  AND r.product_shipment_id = s.id
  AND UPPER(r.status) = 'APROBADA'
  AND UPPER(COALESCE(r.request_type, '')) <> 'OPI';
*/

-- 2b) El ENVI quedó marcado con production_order_id (parece nacido de una OP)
--     y en realidad es un envío de planilla/defectos. Solo entonces:
/*
UPDATE product_shipment s
SET production_order_id = NULL,
    updated_at = NOW()
FROM internal_shipment_request r
WHERE s.shipment_number = 'ENVI-00000'
  AND r.product_shipment_id = s.id
  AND UPPER(r.status) = 'APROBADA'
  AND UPPER(COALESCE(r.request_type, '')) IN ('PLANILLA', 'DEFECTOS');
*/
