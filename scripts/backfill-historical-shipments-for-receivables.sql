-- Relleno de una sola vez: envíos históricos para las órdenes de Luis Felipe que ya tienen cargo pero nunca
-- pasaron por "Preparar envíos" (no tienen ninguna fila en product_shipment; solo tienen su ENVP).
--
-- POR QUÉ EXISTE
-- La cartera ahora solo cuenta lo despachado, y "despachado" es un envío en product_shipment (SENT, DELIVERED,
-- COMPLETED o RECEIVED). Las órdenes viejas se facturaron con el ENVP que se asigna al crear la orden y no tienen
-- ese envío, así que el sistema no puede saber si salieron. Este script les crea uno, en CONFIRMED (no despachado),
-- con el mismo número ENVP de la orden. Quien opera pasa a SENT (Preparar envíos > Enviar) los que sí salieron.
--
-- QUÉ CREA (una fila por orden, en product_shipment; no crea líneas de producto)
--   shipment_number = el ENVP de la orden      status = CONFIRMED
--   created_at = fecha del primer cargo activo de la orden (a las 12:00): al pasarlo a SENT esa será su fecha de salida
--   notes  = 'ENVIO HISTORICO ...' (el sistema lo reconoce: al pasarlo a SENT cuenta como que salió TODA la orden y
--            se marca sin mover inventario, sin pedir productos ni destino)
--   Sin ubicación, sin partial_release_id, sin costo de envío (hereda de la orden).
--
-- QUÉ ÓRDENES TOCA (todas estas condiciones)
--   - vendedor LUIS FELIPE, tipo distinto de INTERNA / CLIENTE_KIOSKO, con cliente y no cancelada;
--   - con ENVP asignado y no anulado (vendor_shipment_voided_at IS NULL);
--   - SIN ninguna fila en product_shipment;
--   - con un cargo activo por la orden completa (product_shipment_id IS NULL);
--   - cuyo ENVP no esté ya usado como shipment_number de otra fila (shipment_number es único).
--
-- EFECTOS A TENER EN CUENTA
--   - Mientras el envío histórico siga en CONFIRMED la orden NO cuenta como despachada: su cargo se muestra recortado
--     a lo ya pagado, o sea sin saldo por cobrar. Su saldo vuelve a verse completo cuando se pasa a SENT.
--   - Al pasar a SENT, el vencimiento se calcula desde la fecha de salida + días de crédito del cliente: esas órdenes
--     tendrán fecha de vencimiento (antes salían "Corriente") y pueden aparecer vencidas.
--
-- ES IDEMPOTENTE: la condición "sin ninguna fila en product_shipment" hace que correrlo dos veces no cambie nada la
-- segunda. NO CONFIRMA POR DEFECTO: termina en ROLLBACK. Revise la vista previa y cambie ROLLBACK por COMMIT.
--
-- Usage: psql -v ON_ERROR_STOP=1 -d fosstest -f backfill-historical-shipments-for-receivables.sql
\set ON_ERROR_STOP on
SELECT current_database() AS base_destino;

BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE hist_candidates ON COMMIT DROP AS
SELECT po.id AS order_id,
       po.code AS order_code,
       po.customer_id,
       btrim(po.vendor_shipment_number) AS shipment_number,
       ch.first_charge_date
FROM production_order po
JOIN (
    SELECT production_order_id, min(entry_date) AS first_charge_date
    FROM customer_account_entry
    WHERE entry_type = 'CHARGE' AND status <> 'VOID' AND product_shipment_id IS NULL
      AND production_order_id IS NOT NULL
    GROUP BY production_order_id
) ch ON ch.production_order_id = po.id
WHERE upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
  AND upper(coalesce(po.order_type, '')) NOT IN ('INTERNA', 'CLIENTE_KIOSKO')
  AND po.customer_id IS NOT NULL
  AND upper(coalesce(po.status, '')) <> 'CANCELLED'
  AND po.vendor_shipment_number IS NOT NULL AND btrim(po.vendor_shipment_number) <> ''
  AND po.vendor_shipment_voided_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM product_shipment s WHERE s.production_order_id = po.id)
  AND NOT EXISTS (SELECT 1 FROM product_shipment s WHERE s.shipment_number = btrim(po.vendor_shipment_number));

-- Vista previa 1: cuántas órdenes se van a rellenar y desde qué fecha.
SELECT count(*) AS ordenes_a_rellenar, min(first_charge_date) AS desde, max(first_charge_date) AS hasta
FROM hist_candidates;

-- Vista previa 2: el detalle (cliente, orden, ENVP y fecha que tomará como salida).
SELECT c.order_id, c.order_code, c.shipment_number, cu.legacy_code, cu.name AS cliente, c.first_charge_date
FROM hist_candidates c
LEFT JOIN customer cu ON cu.id = c.customer_id
ORDER BY c.shipment_number;

-- Vista previa 3: órdenes con cargo y sin envío que NO se rellenan porque su ENVP ya es el shipment_number de otra
-- fila. Revisarlas a mano.
SELECT po.id AS order_id, po.code, btrim(po.vendor_shipment_number) AS envp
FROM production_order po
WHERE upper(coalesce(po.seller_name, '')) LIKE '%LUIS FELIPE%'
  AND po.customer_id IS NOT NULL
  AND po.vendor_shipment_voided_at IS NULL
  AND upper(coalesce(po.status, '')) <> 'CANCELLED'
  AND EXISTS (SELECT 1 FROM customer_account_entry e
              WHERE e.production_order_id = po.id AND e.entry_type = 'CHARGE' AND e.status <> 'VOID'
                AND e.product_shipment_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM product_shipment s WHERE s.production_order_id = po.id)
  AND EXISTS (SELECT 1 FROM product_shipment s WHERE s.shipment_number = btrim(po.vendor_shipment_number));

-- RELLENO
INSERT INTO product_shipment
    (production_order_id, shipment_number, status, notes, created_at, updated_at)
SELECT c.order_id,
       c.shipment_number,
       'CONFIRMED',
       'ENVIO HISTORICO: orden anterior a Preparar envios, creado para cuentas por cobrar. Pasar a SENT si salio.',
       (c.first_charge_date + time '12:00'),
       now()
FROM hist_candidates c;

-- Verificación: debe ser 0 (ninguna orden candidata quedó sin su envío) y el número de envíos creados.
SELECT (SELECT count(*) FROM hist_candidates c
        WHERE NOT EXISTS (SELECT 1 FROM product_shipment s WHERE s.production_order_id = c.order_id)) AS sin_envio,
       (SELECT count(*) FROM product_shipment WHERE notes LIKE 'ENVIO HISTORICO%') AS envios_historicos;

-- Cambie ROLLBACK por COMMIT para confirmar.
ROLLBACK;
