-- ---------------------------------------------------------------------------
-- Anular boletas de solicitud de producto interno (BLS) y borrar todo lo ligado:
--
--   internal_shipment_request_slip  -> UPDATE a VOIDED (la boleta fisica queda anulada, request_id = NULL)
--   internal_shipment_request       -> DELETE (sus lineas internal_shipment_request_line caen por CASCADE)
--   production_order (OPI INTERNA)  -> DELETE, si la solicitud genero orden, junto con:
--       production_order_item, task, task_item, task_item_material_pick, qa_record,
--       production_cincho_day_status, production_order_warehouse_unit (PENDING),
--       material_request / material_request_item (origin = 'ORDEN_PRODUCCION')
--
-- Las boletas se eligen con un IN sobre slip_number (ver tmp_bls_slip en PARTE 1 y PARTE 2).
-- Boletas: BLS-00007 y BLS-00008 (si cambian, reemplazarlas EN AMBAS PARTES).
--
-- Si la solicitud ya fue AUTORIZADA tiene ENVI (product_shipment) y ya se desconto stock de Devoluciones / PT.
-- PASO 0 (en el sistema, no en SQL): Distribucion > Preparar Envios > buscar el ENVI (consulta 1.7) >
--   "Anular / regenerar". Eso regresa el inventario a Devoluciones / Bodega PT y anula el cargo en CxC.
--   El ENVI queda CANCELLED y se conserva (el kardex lo referencia); este script solo lo desliga de la solicitud.
--
-- La PARTE 2 se DETIENE (RAISE EXCEPTION, no borra nada) si:
--   * algun ENVI ligado NO esta CANCELLED (falta el paso 0, o ya fue entregado)
--   * la OPI ya tiene piezas recibidas en Bodega PT, consumo de materiales, movimientos de cuero,
--     liberaciones parciales, cargos vigentes en cuenta por cobrar o solicitudes de materiales COMPRADAS
--
-- Orden: PASO 0 (si aplica) -> PARTE 1 (solo lectura, revisar) -> PARTE 2 (ejecutar todo junto; termina en COMMIT).
-- Probar primero en fosstest; en produccion hacer pg_dump antes.
-- ---------------------------------------------------------------------------


-- ============================== PARTE 1: VER QUE HAY (solo lectura) ==============================

-- 1.1 Boletas en el talonario (PRINTED = disponible, USED = ligada, VOIDED = anulada)
SELECT id, slip_number, status, request_id, printed_at, printed_by
FROM internal_shipment_request_slip
WHERE slip_number IN ('BLS-00007', 'BLS-00008');

-- 1.2 Solicitudes de esas boletas (product_shipment_id NO nulo = ya autorizada con ENVI -> la parte 2 se detiene)
SELECT id, status, request_type, recipient_name, employee_id, slip_number, document_date, requested_at,
       product_shipment_id, production_order_id, opi_authorized_at
FROM internal_shipment_request
WHERE slip_number IN ('BLS-00007', 'BLS-00008');

-- 1.3 Lineas de las solicitudes
SELECT l.request_id, l.line_order, p.code, p.name, l.color_id, l.size, l.quantity
FROM internal_shipment_request_line l
JOIN internal_shipment_request r ON r.id = l.request_id
LEFT JOIN product p ON p.id = l.product_id
WHERE r.slip_number IN ('BLS-00007', 'BLS-00008')
ORDER BY l.request_id, l.line_order;

-- 1.4 OPI generada por la solicitud (DRAFT = aun no autorizada; PENDING/IN_PROGRESS = ya tiene tareas/materiales)
SELECT po.id, po.code, po.order_type, po.status, po.created_at, po.observations
FROM production_order po
WHERE po.id IN (
    SELECT production_order_id FROM internal_shipment_request
    WHERE slip_number IN ('BLS-00007', 'BLS-00008') AND production_order_id IS NOT NULL);

-- 1.5 Conteo de todo lo ligado a esas OPI / solicitudes (lo que la parte 2 borra o la detiene)
WITH req AS (
    SELECT id, product_shipment_id, production_order_id
    FROM internal_shipment_request
    WHERE slip_number IN ('BLS-00007', 'BLS-00008')
), po AS (
    SELECT production_order_id AS id FROM req WHERE production_order_id IS NOT NULL
)
SELECT 'BORRA  production_order_item' AS que, COUNT(*) AS filas FROM production_order_item WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'BORRA  task', COUNT(*) FROM task WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'BORRA  task_item', COUNT(*) FROM task_item WHERE task_id IN (SELECT id FROM task WHERE production_order_id IN (SELECT id FROM po))
UNION ALL SELECT 'BORRA  qa_record', COUNT(*) FROM qa_record WHERE production_order_id IN (SELECT id FROM po)
       OR task_id IN (SELECT id FROM task WHERE production_order_id IN (SELECT id FROM po))
UNION ALL SELECT 'BORRA  production_cincho_day_status', COUNT(*) FROM production_cincho_day_status WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'BORRA  production_order_warehouse_unit PENDING', COUNT(*) FROM production_order_warehouse_unit WHERE production_order_id IN (SELECT id FROM po) AND receipt_status = 'PENDING'
UNION ALL SELECT 'BORRA  material_request (no COMPRADA)', COUNT(*) FROM material_request WHERE origin = 'ORDEN_PRODUCCION' AND origin_reference_id IN (SELECT id FROM po) AND UPPER(status) <> 'COMPRADA'
UNION ALL SELECT 'DETIENE ENVI no anulado (hacer paso 0)', COUNT(*) FROM product_shipment WHERE (id IN (SELECT product_shipment_id FROM req) OR production_order_id IN (SELECT id FROM po)) AND UPPER(status) <> 'CANCELLED'
UNION ALL SELECT 'DETIENE task_item_material_pick', COUNT(*) FROM task_item_material_pick WHERE task_item_id IN (SELECT ti.id FROM task_item ti JOIN task t ON t.id = ti.task_id WHERE t.production_order_id IN (SELECT id FROM po))
UNION ALL SELECT 'DETIENE warehouse_unit recibidas/rechazadas', COUNT(*) FROM production_order_warehouse_unit WHERE production_order_id IN (SELECT id FROM po) AND receipt_status <> 'PENDING'
UNION ALL SELECT 'DETIENE material_consumption', COUNT(*) FROM material_consumption WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'DETIENE leather_movement', COUNT(*) FROM leather_movement WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'DETIENE production_order_partial_release', COUNT(*) FROM production_order_partial_release WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'DETIENE customer_account_entry vigente', COUNT(*) FROM customer_account_entry WHERE voided_at IS NULL AND (production_order_id IN (SELECT id FROM po) OR product_shipment_id IN (SELECT product_shipment_id FROM req))
UNION ALL SELECT 'DETIENE online_sale ligada a la OPI', COUNT(*) FROM online_sale WHERE production_order_id IN (SELECT id FROM po)
UNION ALL SELECT 'DETIENE material_request COMPRADA', COUNT(*) FROM material_request WHERE origin = 'ORDEN_PRODUCCION' AND origin_reference_id IN (SELECT id FROM po) AND UPPER(status) = 'COMPRADA';

-- 1.6 Tareas de la OPI (detalle)
SELECT t.id, t.production_order_id, t.production_order_item_id, t.status, t.created_at
FROM task t
WHERE t.production_order_id IN (
    SELECT production_order_id FROM internal_shipment_request
    WHERE slip_number IN ('BLS-00007', 'BLS-00008') AND production_order_id IS NOT NULL)
ORDER BY t.id;

-- 1.7 ENVI de las solicitudes: el numero para buscarlo en Preparar Envios y su estado
--     (SENT / CONFIRMED / DRAFT = anular en el sistema; CANCELLED = listo; DELIVERED = no se puede anular)
SELECT r.slip_number, ps.id, ps.shipment_number, ps.status, ps.sent_at, ps.created_at
FROM product_shipment ps
JOIN internal_shipment_request r ON r.product_shipment_id = ps.id OR r.production_order_id = ps.production_order_id
WHERE r.slip_number IN ('BLS-00007', 'BLS-00008')
ORDER BY ps.id;


-- ============================== PARTE 2: ANULAR Y BORRAR (ejecutar todo junto) ==============================
-- Si alguna validacion falla se detiene con un mensaje y no se toca nada: ejecutar ROLLBACK.

BEGIN;

-- Boletas a anular (cambiar aqui tambien)
CREATE TEMP TABLE tmp_bls_slip ON COMMIT DROP AS
SELECT UNNEST(ARRAY['BLS-00007', 'BLS-00008'])::varchar AS slip_number;

CREATE TEMP TABLE tmp_req ON COMMIT DROP AS
SELECT r.id, r.slip_number, r.status, r.product_shipment_id, r.production_order_id
FROM internal_shipment_request r
WHERE r.slip_number IN (SELECT slip_number FROM tmp_bls_slip);

CREATE TEMP TABLE tmp_po ON COMMIT DROP AS
SELECT DISTINCT production_order_id AS id FROM tmp_req WHERE production_order_id IS NOT NULL;

CREATE TEMP TABLE tmp_task ON COMMIT DROP AS
SELECT id FROM task WHERE production_order_id IN (SELECT id FROM tmp_po);

CREATE TEMP TABLE tmp_mr ON COMMIT DROP AS
SELECT id FROM material_request
WHERE origin = 'ORDEN_PRODUCCION' AND origin_reference_id IN (SELECT id FROM tmp_po);

-- Validaciones
DO $$
DECLARE
    v_n bigint;
    v_envi text;
BEGIN
    SELECT COUNT(*) INTO v_n FROM internal_shipment_request_slip WHERE slip_number IN (SELECT slip_number FROM tmp_bls_slip);
    IF v_n <> (SELECT COUNT(*) FROM tmp_bls_slip) THEN
        RAISE EXCEPTION 'No se encontraron todas las boletas en el talonario (encontradas %). Revisar los numeros.', v_n;
    END IF;

    SELECT string_agg(COALESCE(ps.shipment_number, '#' || ps.id) || ' (' || ps.status || ')', ', ')
    INTO v_envi
    FROM product_shipment ps
    WHERE (ps.id IN (SELECT product_shipment_id FROM tmp_req) OR ps.production_order_id IN (SELECT id FROM tmp_po))
      AND UPPER(ps.status) <> 'CANCELLED';
    IF v_envi IS NOT NULL THEN
        RAISE EXCEPTION 'ENVI sin anular: %. Anularlos en Preparar Envios > "Anular / regenerar" (regresa el inventario) y volver a correr.', v_envi;
    END IF;

    -- La OPI solo se borra si es INTERNA y no la usa otra solicitud fuera de estas boletas
    IF EXISTS (SELECT 1 FROM production_order WHERE id IN (SELECT id FROM tmp_po) AND UPPER(COALESCE(order_type, '')) <> 'INTERNA') THEN
        RAISE EXCEPTION 'La orden ligada no es de tipo INTERNA. Revisar antes de borrar.';
    END IF;
    IF EXISTS (SELECT 1 FROM internal_shipment_request
               WHERE production_order_id IN (SELECT id FROM tmp_po) AND id NOT IN (SELECT id FROM tmp_req)) THEN
        RAISE EXCEPTION 'La OPI esta ligada a otra solicitud que no esta en la lista. Revisar antes de borrar.';
    END IF;

    IF EXISTS (SELECT 1 FROM task_item_material_pick WHERE task_item_id IN (SELECT id FROM task_item WHERE task_id IN (SELECT id FROM tmp_task))) THEN
        RAISE EXCEPTION 'Las tareas ya tienen materiales despachados (task_item_material_pick).';
    END IF;
    IF EXISTS (SELECT 1 FROM production_order_warehouse_unit WHERE production_order_id IN (SELECT id FROM tmp_po) AND receipt_status <> 'PENDING') THEN
        RAISE EXCEPTION 'La OPI ya tiene piezas recibidas/rechazadas en Bodega PT.';
    END IF;
    IF EXISTS (SELECT 1 FROM material_consumption WHERE production_order_id IN (SELECT id FROM tmp_po)) THEN
        RAISE EXCEPTION 'La OPI ya tiene consumo de materiales (material_consumption).';
    END IF;
    IF EXISTS (SELECT 1 FROM leather_movement WHERE production_order_id IN (SELECT id FROM tmp_po)) THEN
        RAISE EXCEPTION 'La OPI ya tiene movimientos de cuero (leather_movement).';
    END IF;
    IF EXISTS (SELECT 1 FROM production_order_partial_release WHERE production_order_id IN (SELECT id FROM tmp_po)) THEN
        RAISE EXCEPTION 'La OPI tiene liberaciones parciales.';
    END IF;
    IF EXISTS (SELECT 1 FROM customer_account_entry
               WHERE voided_at IS NULL
                 AND (production_order_id IN (SELECT id FROM tmp_po)
                      OR product_shipment_id IN (SELECT product_shipment_id FROM tmp_req))) THEN
        RAISE EXCEPTION 'Hay cargos vigentes en cuenta por cobrar (customer_account_entry) del ENVI u OPI.';
    END IF;
    IF EXISTS (SELECT 1 FROM online_sale WHERE production_order_id IN (SELECT id FROM tmp_po)) THEN
        RAISE EXCEPTION 'La OPI esta ligada a una venta en linea.';
    END IF;
    IF EXISTS (SELECT 1 FROM material_request WHERE id IN (SELECT id FROM tmp_mr) AND UPPER(status) = 'COMPRADA') THEN
        RAISE EXCEPTION 'La OPI tiene solicitudes de materiales ya COMPRADAS.';
    END IF;

    RAISE NOTICE 'Solicitudes: %, OPI: %, tareas: %, solicitudes de materiales: %',
        (SELECT COUNT(*) FROM tmp_req), (SELECT COUNT(*) FROM tmp_po),
        (SELECT COUNT(*) FROM tmp_task), (SELECT COUNT(*) FROM tmp_mr);
END $$;

-- 1) Anular la boleta fisica (queda VOIDED; para volver a usar el numero poner 'PRINTED' en lugar de 'VOIDED')
UPDATE internal_shipment_request_slip
SET status = 'VOIDED',
    request_id = NULL,
    updated_at = NOW()
WHERE slip_number IN (SELECT slip_number FROM tmp_bls_slip);

-- 2) Desligar OPI y ENVI de la solicitud antes de borrar (el ENVI CANCELLED se conserva por el kardex)
UPDATE internal_shipment_request
SET production_order_id = NULL,
    product_shipment_id = NULL
WHERE id IN (SELECT id FROM tmp_req);
UPDATE product_shipment
SET production_order_id = NULL
WHERE production_order_id IN (SELECT id FROM tmp_po)
  AND UPPER(status) = 'CANCELLED';
UPDATE customer_account_entry
SET production_order_id = NULL
WHERE production_order_id IN (SELECT id FROM tmp_po)
  AND voided_at IS NOT NULL;

-- 3) Tareas de la OPI
DELETE FROM qa_record
WHERE task_id IN (SELECT id FROM tmp_task) OR production_order_id IN (SELECT id FROM tmp_po);
DELETE FROM task_item WHERE task_id IN (SELECT id FROM tmp_task);
DELETE FROM task WHERE id IN (SELECT id FROM tmp_task);

-- 4) Solicitudes de materiales generadas al autorizar la OPI
DELETE FROM material_request_item WHERE material_request_id IN (SELECT id FROM tmp_mr);
DELETE FROM material_request WHERE id IN (SELECT id FROM tmp_mr);

-- 5) Resto de la OPI
DELETE FROM production_cincho_day_status WHERE production_order_id IN (SELECT id FROM tmp_po);
DELETE FROM production_order_warehouse_unit WHERE production_order_id IN (SELECT id FROM tmp_po);
DELETE FROM production_order_item WHERE production_order_id IN (SELECT id FROM tmp_po);
DELETE FROM production_order WHERE id IN (SELECT id FROM tmp_po);

-- 6) Solicitud (las lineas se borran por ON DELETE CASCADE)
DELETE FROM internal_shipment_request_line WHERE request_id IN (SELECT id FROM tmp_req);
DELETE FROM internal_shipment_request WHERE id IN (SELECT id FROM tmp_req);

-- Verificacion (todo debe salir 0 y las boletas en VOIDED)
SELECT 'internal_shipment_request' AS tabla, COUNT(*) AS filas FROM internal_shipment_request WHERE id IN (SELECT id FROM tmp_req)
UNION ALL SELECT 'production_order', COUNT(*) FROM production_order WHERE id IN (SELECT id FROM tmp_po)
UNION ALL SELECT 'production_order_item', COUNT(*) FROM production_order_item WHERE production_order_id IN (SELECT id FROM tmp_po)
UNION ALL SELECT 'task', COUNT(*) FROM task WHERE id IN (SELECT id FROM tmp_task)
UNION ALL SELECT 'material_request', COUNT(*) FROM material_request WHERE id IN (SELECT id FROM tmp_mr)
UNION ALL SELECT 'slip no VOIDED', COUNT(*) FROM internal_shipment_request_slip
          WHERE slip_number IN (SELECT slip_number FROM tmp_bls_slip) AND status <> 'VOIDED';

COMMIT;
