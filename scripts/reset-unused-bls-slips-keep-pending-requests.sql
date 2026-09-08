-- Reinicia el talonario BLS para volver a imprimir desde el inicio,
-- SIN borrar solicitudes ni envíos pendientes.
--
-- Qué NO toca:
--   - internal_shipment_request (solicitudes PENDIENTE/APROBADA/RECHAZADA)
--   - product_shipment (ENVI ya generados)
--   - production_order (OPI)
--
-- Cómo usarlo:
--   1) Corre el bloque 1 (consulta).
--   2) Si está bien, corre el bloque 2 (borra solo boletas no usadas).
--   3) Si una solicitud tiene BLS-00001 pero nunca se llenó la boleta física,
--      corre el bloque 3: le quita el 1 a esa solicitud y no la borra.

-- ============================================================
-- 1) VER ESTADO (no cambia nada)
-- ============================================================

SELECT status, COUNT(*) AS cantidad,
       MIN(slip_number) AS desde,
       MAX(slip_number) AS hasta
FROM internal_shipment_request_slip
GROUP BY status
ORDER BY status;

SELECT id, slip_number, status, request_id, printed_at
FROM internal_shipment_request_slip
ORDER BY slip_number;

SELECT id, status, request_type, recipient_name, slip_number, product_shipment_id
FROM internal_shipment_request
ORDER BY id;

-- Próximo número que usaría el sistema hoy (máximo BLS en talonario o en solicitudes + 1)
SELECT CONCAT('BLS-', LPAD((
    SELECT COALESCE(MAX(seq), 0) + 1
    FROM (
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER) AS seq
        FROM internal_shipment_request_slip
        WHERE slip_number ~ '^BLS-[0-9]+$'
        UNION ALL
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER)
        FROM internal_shipment_request
        WHERE slip_number ~ '^BLS-[0-9]+$'
    ) x
)::text, 5, '0')) AS proximo_correlativo;


-- ============================================================
-- 2) BORRAR SOLO BOLETAS NO USADAS
--    No elimina la solicitud pendiente.
--    Si alguna solicitud ya tiene BLS-00007, el próximo print
--    seguirá saliendo DESPUÉS de ese número.
-- ============================================================

BEGIN;

DELETE FROM internal_shipment_request_slip
WHERE UPPER(COALESCE(status, '')) IN ('PRINTED', 'VOIDED')
  AND request_id IS NULL;

-- Verificación: las solicitudes siguen
SELECT COUNT(*) AS solicitudes_que_siguen
FROM internal_shipment_request;

SELECT CONCAT('BLS-', LPAD((
    SELECT COALESCE(MAX(seq), 0) + 1
    FROM (
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER) AS seq
        FROM internal_shipment_request_slip
        WHERE slip_number ~ '^BLS-[0-9]+$'
        UNION ALL
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER)
        FROM internal_shipment_request
        WHERE slip_number ~ '^BLS-[0-9]+$'
    ) x
)::text, 5, '0')) AS proximo_correlativo_despues_del_delete;

COMMIT;
-- Si algo se ve mal: ROLLBACK;  (en vez de COMMIT)


-- ============================================================
-- 3) SOLTAR BLS-00001 DE LA SOLICITUD QUE NUNCA LLENARON
--    La solicitud queda. Solo se le quita el número 1 para que
--    el talonario pueda volver a imprimir desde BLS-00001.
-- ============================================================

BEGIN;

-- Confirmar cuál es (debe ser 1 fila)
SELECT id, status, request_type, recipient_name, slip_number, product_shipment_id
FROM internal_shipment_request
WHERE slip_number IN ('BLS-00001', 'BLS-1', '1');

-- Quitar el número 1 de esa solicitud (NO la borra)
UPDATE internal_shipment_request
SET slip_number = NULL
WHERE slip_number IN ('BLS-00001', 'BLS-1', '1');

-- Liberar la boleta 1 del talonario
DELETE FROM internal_shipment_request_slip
WHERE slip_number IN ('BLS-00001', 'BLS-1', '1');

-- Borrar el resto de boletas impresas y no usadas
DELETE FROM internal_shipment_request_slip
WHERE UPPER(COALESCE(status, '')) IN ('PRINTED', 'VOIDED')
  AND request_id IS NULL;

-- La solicitud sigue:
SELECT id, status, request_type, recipient_name, slip_number
FROM internal_shipment_request
ORDER BY id;

SELECT CONCAT('BLS-', LPAD((
    SELECT COALESCE(MAX(seq), 0) + 1
    FROM (
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER) AS seq
        FROM internal_shipment_request_slip
        WHERE slip_number ~ '^BLS-[0-9]+$'
        UNION ALL
        SELECT CAST(SUBSTRING(slip_number FROM 5) AS INTEGER)
        FROM internal_shipment_request
        WHERE slip_number ~ '^BLS-[0-9]+$'
    ) x
)::text, 5, '0')) AS proximo_correlativo;

COMMIT;
-- Si el SELECT de confirmación no era la solicitud correcta: ROLLBACK;
