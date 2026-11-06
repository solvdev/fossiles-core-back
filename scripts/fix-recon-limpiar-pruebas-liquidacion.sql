-- Limpia las pruebas hechas en Conciliación de ventas en línea (2026-10-19) para que las ventas
-- vuelvan a aparecer SIN liquidar.
--
-- Qué se tocó en la prueba (verificado contra prod el 2026-11-06):
--   · recon_receipt 1 y 2 (CARRIER, "captura escrita en el caso") con su recon_receipt_application
--     -> casos 90 (venta #9, Q615) y 89 (venta #8, Q699 con Q689 recibidos).
--   · recon_event de esos casos: FONDO_CAPTURADO, FONDO_APLICADO, ESTADO_CAMBIO.
--   · Los casos 89 y 90 quedaron en PENDIENTE_REVISION / CON_DIFERENCIAS con montos recibidos.
-- No hay lotes (recon_batch vacío), ni cierres, ni diferencias, ni exportaciones: nada más que limpiar.
--
-- Los CARGO del libro (recon_ledger_entry) y CASO_CREADO son del alta normal de los casos: se dejan.
-- recon_event es append-only (trigger), por eso se apaga el trigger solo dentro de esta transacción.
-- Idempotente: si ya se corrió, no encuentra nada y no cambia nada.

BEGIN;

-- Salvaguarda: solo se limpian recibos/aplicaciones que sean exactamente los de la prueba.
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM recon_receipt WHERE id IN (1, 2)
        AND raw_payload = '{"captura":"escrita en el caso"}' AND received_date = DATE '2026-10-19';
    IF n NOT IN (0, 2) THEN
        RAISE EXCEPTION 'Los recibos 1 y 2 no son los de la prueba (%): revisar antes de borrar', n;
    END IF;
    IF EXISTS (SELECT 1 FROM recon_batch) THEN
        RAISE EXCEPTION 'Ya existen lotes de liquidación: este script solo es para antes del primer lote';
    END IF;
END $$;

DELETE FROM recon_receipt_application WHERE id IN (1, 2) AND case_id IN (89, 90) AND receipt_id IN (1, 2);
DELETE FROM recon_receipt WHERE id IN (1, 2) AND raw_payload = '{"captura":"escrita en el caso"}';

ALTER TABLE recon_event DISABLE TRIGGER trg_recon_event_append_only;
DELETE FROM recon_event
 WHERE case_id IN (89, 90) AND event_type IN ('FONDO_CAPTURADO', 'FONDO_APLICADO', 'ESTADO_CAMBIO')
   AND created_at BETWEEN TIMESTAMP '2026-10-19 20:35:00' AND TIMESTAMP '2026-10-19 20:37:00';
ALTER TABLE recon_event ENABLE TRIGGER trg_recon_event_append_only;

-- Mismo estado que las demás ventas históricas sin guía ni fondo (ej. casos 84-88, 91-95).
UPDATE recon_case
   SET received_amount = 0,
       difference = 0,
       authorized_adjustment = 0,
       pending_balance = expected_amount,
       status = 'INFORMACION_INCOMPLETA',
       problem_type = 'VENTA_SIN_ENVIO',
       critical_count = 1,
       warning_count = 1,
       updated_at = now()
 WHERE id IN (89, 90) AND batch_id IS NULL AND closed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM recon_receipt_application a WHERE a.case_id = recon_case.id);

-- Revisión (debe mostrar 0 recibos, 0 aplicaciones y los dos casos sin liquidar)
SELECT (SELECT count(*) FROM recon_receipt) AS recibos,
       (SELECT count(*) FROM recon_receipt_application) AS aplicaciones,
       (SELECT count(*) FROM recon_event WHERE event_type IN ('FONDO_CAPTURADO','FONDO_APLICADO','ESTADO_CAMBIO')) AS eventos_prueba,
       (SELECT count(*) FROM recon_case WHERE received_amount <> 0 OR batch_id IS NOT NULL OR closed_at IS NOT NULL) AS casos_con_cobro_o_lote;
SELECT id, sale_number, status, problem_type, expected_amount, received_amount, pending_balance
  FROM recon_case WHERE id IN (89, 90) ORDER BY id;

COMMIT;
