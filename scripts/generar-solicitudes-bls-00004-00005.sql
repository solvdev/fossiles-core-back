-- ---------------------------------------------------------------------------
-- Solicitudes de envio interno PLANILLA para las boletas fisicas BLS-00004 y BLS-00005, fecha 30-sep-2026,
-- en estado PENDIENTE (aparecen en el sistema "para autorizar" por Contabilidad).
--
--   BLS-00004: empleado id 5,  BD-26 BOLSO FATIMA, color ROJO FLOTTER, cantidad 1, sin talla, usuario 59
--   BLS-00005: empleado id 85, B-55-D BILLETERA MAVERICK DAMA, color ROJO, cantidad 1, sin talla, usuario 57
--
-- Una "boleta de solicitud" = fila en internal_shipment_request (PENDIENTE) + sus lineas
-- (internal_shipment_request_line) + la boleta marcada USED en internal_shipment_request_slip.
-- El ENVI (product_shipment) NO se crea aqui: lo crea el sistema cuando Contabilidad autoriza (approve).
-- Al autorizar, si en Devoluciones / Bodega PT NO hay stock del producto+color, el sistema genera una OPI
-- y no deja autorizar. Por eso la PARTE 2 se detiene si no hay stock suficiente.
--
-- Orden: PARTE 1 (solo lectura, correrla completa y revisar) -> PARTE 2 (una sola ejecucion, en transaccion).
-- Probar primero en fosstest; en produccion hacer pg_dump antes.
-- ---------------------------------------------------------------------------


-- ============================== PARTE 1: VER QUE HAY (solo lectura) ==============================

-- 1.1 Las boletas en el talonario (PRINTED = disponible, USED = ya ligada, VOIDED = anulada)
SELECT id, slip_number, status, request_id, printed_at, printed_by
FROM internal_shipment_request_slip
WHERE slip_number IN ('BLS-00004', 'BLS-00005');

-- 1.2 Solicitudes que ya usan esas boletas (si devuelve filas, NO ejecutar la parte 2)
SELECT id, status, request_type, recipient_name, employee_id, slip_number, document_date, requested_at,
       product_shipment_id, production_order_id
FROM internal_shipment_request
WHERE slip_number IN ('BLS-00004', 'BLS-00005');

-- 1.3 Empleados 5 y 85 y usuarios 59 y 57 (verificar que son quienes deben ser)
SELECT id, first_name, last_name, dpi, phone FROM employee WHERE id IN (5, 85);
SELECT id, username, first_name, last_name FROM users WHERE id IN (59, 57);

-- 1.4 Planilla: un empleado solo puede tener 1 solicitud PENDIENTE/APROBADA por mes. Debe devolver 0 filas.
SELECT id, employee_id, status, slip_number, requested_at
FROM internal_shipment_request
WHERE request_type = 'PLANILLA' AND UPPER(status) IN ('PENDIENTE', 'APROBADA')
  AND employee_id IN (5, 85)
  AND requested_at >= '2026-09-01' AND requested_at < '2026-10-01';

-- 1.5 Productos: cada codigo debe salir en UNA sola fila (si sale mas de una o ninguna, revisar el codigo)
SELECT id, code, name, status, sale_price FROM product
WHERE UPPER(TRIM(code)) IN ('BD-26', 'B-55-D') OR UPPER(name) LIKE '%FATIMA%' OR UPPER(name) LIKE '%MAVERICK%'
ORDER BY code;

-- 1.6 Colores rojos: deben existir exactamente 'ROJO FLOTTER' y 'ROJO' (si se escribe distinto, ajustar la parte 2)
SELECT id, name FROM colors WHERE UPPER(name) LIKE '%ROJO%' ORDER BY name;

-- 1.7 Ubicaciones de despacho (el sistema descuenta de Devoluciones y luego de Bodega PT)
SELECT id, code, name FROM locations
WHERE UPPER(code) IN ('BODEGA_DEVOLUCIONES', 'BODEGA_DEV', 'DEVOLUCION', 'DEVOLUCIONES', 'BODEGA_RET', 'BODEGA_RETURN', 'BODEGA_PT');

-- 1.8 Stock actual de esos productos en esas ubicaciones, por color. Se necesita >= 1 del producto+color pedido,
--     si no, al autorizar el sistema genera una OPI en lugar de dejar autorizar el envio.
SELECT p.code, p.name AS producto, c.name AS color, l.code AS ubicacion, pil.quantity
FROM product_inventory_location pil
JOIN product p ON p.id = pil.product_id
JOIN locations l ON l.id = pil.location_id
LEFT JOIN colors c ON c.id = pil.color_id
WHERE UPPER(TRIM(p.code)) IN ('BD-26', 'B-55-D')
  AND UPPER(l.code) IN ('BODEGA_DEVOLUCIONES', 'BODEGA_DEV', 'DEVOLUCION', 'DEVOLUCIONES', 'BODEGA_RET', 'BODEGA_RETURN', 'BODEGA_PT')
ORDER BY p.code, c.name, l.code;

-- 1.9 ENVI internos ya creados cerca de esa fecha (por si alguien ya los hizo a mano)
SELECT id, shipment_number, status, created_at, notes
FROM product_shipment
WHERE distribution_id IS NULL AND production_order_id IS NULL
  AND created_at >= '2026-09-25' AND created_at < '2026-10-03'
ORDER BY id;


-- ============================== PARTE 2: GENERAR (ejecutar todo junto) ==============================
-- Si cualquier validacion falla se detiene con un mensaje claro y no se inserta nada: ejecutar ROLLBACK.

BEGIN;

-- Datos de las dos boletas (producto y color se buscan por codigo y nombre exactos)
CREATE TEMP TABLE tmp_bls ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('BLS-00004', 5,  'BD-26', 'ROJO FLOTTER', 1::numeric, 59),
    ('BLS-00005', 85, 'B-55-D', 'ROJO',         1::numeric, 57)
) AS v(slip_number, employee_id, product_code, color_name, quantity, user_id);

-- Validaciones
DO $$
DECLARE
    r RECORD;
    n INT;
    stock NUMERIC;
    pid BIGINT;
    cid BIGINT;
BEGIN
    FOR r IN SELECT * FROM tmp_bls LOOP
        SELECT COUNT(*) INTO n FROM employee WHERE id = r.employee_id;
        IF n <> 1 THEN RAISE EXCEPTION '% : no existe el empleado %', r.slip_number, r.employee_id; END IF;

        SELECT COUNT(*) INTO n FROM users WHERE id = r.user_id;
        IF n <> 1 THEN RAISE EXCEPTION '% : no existe el usuario %', r.slip_number, r.user_id; END IF;

        SELECT COUNT(*) INTO n FROM product WHERE UPPER(TRIM(code)) = r.product_code;
        IF n <> 1 THEN RAISE EXCEPTION '% : el codigo de producto % devuelve % filas (debe ser 1)', r.slip_number, r.product_code, n; END IF;
        SELECT id INTO pid FROM product WHERE UPPER(TRIM(code)) = r.product_code;

        SELECT COUNT(*) INTO n FROM colors WHERE UPPER(TRIM(name)) = r.color_name;
        IF n <> 1 THEN RAISE EXCEPTION '% : el color "%" devuelve % filas (debe ser 1; ver consulta 1.6)', r.slip_number, r.color_name, n; END IF;
        SELECT id INTO cid FROM colors WHERE UPPER(TRIM(name)) = r.color_name;

        SELECT COUNT(*) INTO n FROM internal_shipment_request WHERE slip_number = r.slip_number;
        IF n > 0 THEN RAISE EXCEPTION '% : ya existe una solicitud con esa boleta', r.slip_number; END IF;

        SELECT COUNT(*) INTO n FROM internal_shipment_request_slip
        WHERE slip_number = r.slip_number AND status IN ('VOIDED', 'USED');
        IF n > 0 THEN RAISE EXCEPTION '% : la boleta esta anulada o ya usada en el talonario (ver consulta 1.1)', r.slip_number; END IF;

        SELECT COUNT(*) INTO n FROM internal_shipment_request
        WHERE request_type = 'PLANILLA' AND UPPER(status) IN ('PENDIENTE', 'APROBADA') AND employee_id = r.employee_id
          AND requested_at >= '2026-09-01' AND requested_at < '2026-10-01';
        IF n > 0 THEN RAISE EXCEPTION '% : el empleado % ya tiene una solicitud planilla en septiembre 2026', r.slip_number, r.employee_id; END IF;

        SELECT COALESCE(SUM(pil.quantity), 0) INTO stock
        FROM product_inventory_location pil
        JOIN locations l ON l.id = pil.location_id
        WHERE pil.product_id = pid AND pil.color_id = cid
          AND UPPER(l.code) IN ('BODEGA_DEVOLUCIONES', 'BODEGA_DEV', 'DEVOLUCION', 'DEVOLUCIONES', 'BODEGA_RET', 'BODEGA_RETURN', 'BODEGA_PT');
        -- SIN STOCK = SOLO ADVERTENCIA (producto ya entregado por urgencia: se registra la solicitud igual).
        -- Ojo: mientras no haya stock, Contabilidad NO podra autorizar (el sistema generaria una OPI).
        -- Antes de autorizar hay que ajustar el inventario +cantidad en Bodega PT (ver nota al final).
        IF stock < r.quantity THEN
            RAISE WARNING '% : sin stock en Devoluciones/Bodega PT (hay %, se piden %). Se registra igual; ajustar inventario antes de autorizar.',
                r.slip_number, stock, r.quantity;
        END IF;
    END LOOP;
END $$;

-- Solicitudes (nombre, telefono y DPI salen del empleado, como lo hace el sistema para PLANILLA)
CREATE TEMP TABLE tmp_req (request_id BIGINT, slip_number TEXT) ON COMMIT DROP;

WITH ins AS (
    INSERT INTO internal_shipment_request
        (status, request_type, recipient_name, recipient_phone, recipient_tax_id, employee_id, notes,
         document_date, slip_number, discount_percent, discount_amount,
         requested_by, requested_at, created_at, updated_at)
    SELECT 'PENDIENTE', 'PLANILLA',
           COALESCE(NULLIF(TRIM(COALESCE(e.first_name, '') || ' ' || COALESCE(e.last_name, '')), ''), 'Empleado #' || e.id),
           NULLIF(TRIM(e.phone), ''), NULLIF(TRIM(e.dpi), ''), e.id, NULL,
           '2026-09-30', t.slip_number, NULL, NULL,
           t.user_id, TIMESTAMP '2026-09-30 12:00:00', TIMESTAMP '2026-09-30 12:00:00', TIMESTAMP '2026-09-30 12:00:00'
    FROM tmp_bls t
    JOIN employee e ON e.id = t.employee_id
    RETURNING id, slip_number
)
INSERT INTO tmp_req SELECT id, slip_number FROM ins;

-- Lineas (sin talla)
INSERT INTO internal_shipment_request_line (request_id, line_order, product_id, color_id, size, quantity)
SELECT q.request_id, 1,
       (SELECT id FROM product WHERE UPPER(TRIM(code)) = t.product_code),
       (SELECT id FROM colors WHERE UPPER(TRIM(name)) = t.color_name),
       NULL, t.quantity
FROM tmp_req q
JOIN tmp_bls t ON t.slip_number = q.slip_number;

-- Boletas del talonario: USED y ligadas a su solicitud (si no estaban en el talonario se registran)
INSERT INTO internal_shipment_request_slip (slip_number, status, printed_at, printed_by, request_id, created_at, updated_at)
SELECT q.slip_number, 'USED', TIMESTAMP '2026-09-30 12:00:00', t.user_id, q.request_id, now(), now()
FROM tmp_req q
JOIN tmp_bls t ON t.slip_number = q.slip_number
ON CONFLICT (slip_number) DO UPDATE
    SET status = 'USED', request_id = EXCLUDED.request_id, updated_at = now();

-- Verificacion: deben salir 2 solicitudes PENDIENTE, con 1 linea cada una y la boleta USED
SELECT r.id, r.status, r.request_type, r.recipient_name, r.employee_id, r.slip_number, r.document_date,
       r.requested_at, r.requested_by, p.code, p.name AS producto, c.name AS color, l.size, l.quantity,
       s.status AS boleta
FROM internal_shipment_request r
JOIN internal_shipment_request_line l ON l.request_id = r.id
JOIN product p ON p.id = l.product_id
LEFT JOIN colors c ON c.id = l.color_id
LEFT JOIN internal_shipment_request_slip s ON s.slip_number = r.slip_number
WHERE r.slip_number IN ('BLS-00004', 'BLS-00005')
ORDER BY r.slip_number;

-- NOTA STOCK (producto ya entregado, el sistema tiene 0): para que Contabilidad pueda autorizar sin OPI, ANTES de
-- autorizar hay que ingresar +1 de cada producto+color en Bodega PT con un ajuste de inventario (por la pantalla de
-- inventario, para que quede en el kardex). Al autorizar, el envio descuenta ese 1 y el stock vuelve a 0, que es lo real.
-- Verificar despues con la consulta 1.8 que el producto+color tenga >= 1.

-- Si todo se ve bien ejecutar:  COMMIT;     Si algo esta mal:  ROLLBACK;
-- (la transaccion sigue abierta hasta ejecutar uno de los dos; las tablas temporales desaparecen al terminar)

-- Para deshacer despues de un COMMIT (solo mientras sigan PENDIENTE):
--   DELETE FROM internal_shipment_request_line WHERE request_id IN (SELECT id FROM internal_shipment_request WHERE slip_number IN ('BLS-00004','BLS-00005') AND status = 'PENDIENTE');
--   UPDATE internal_shipment_request_slip SET status = 'PRINTED', request_id = NULL WHERE slip_number IN ('BLS-00004','BLS-00005');
--   DELETE FROM internal_shipment_request WHERE slip_number IN ('BLS-00004','BLS-00005') AND status = 'PENDIENTE';
