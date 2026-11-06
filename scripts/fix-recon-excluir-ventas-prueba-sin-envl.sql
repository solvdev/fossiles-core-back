-- Oculta de Conciliación en línea las ventas de prueba que NO tienen número ENVL
-- (24 casos históricos de mar-may 2026, ventas "1", "2", "3-OP"...).
--
-- Requiere el back con ReconciliationSpecs.HIDDEN_REASON_PREFIX = 'PRUEBA:' (rama hotfix/recon-ocultar-ventas-prueba):
-- los casos con ese motivo no salen en la lista, ni en "Anuladas", ni en los totales.
-- Antes de desplegar ese back, estos casos se ven como "Anuladas".
--
-- Los casos NO se borran: el front los vuelve a crear al abrir la pantalla y el libro es append-only.
-- Se excluyen como lo hace la app (ReconciliationCaseService.exclude): reversa del CARGO + evento EXCLUIDO.
-- No toca online_sale. Idempotente. Si hace falta traerlos de vuelta: poner excluded_reason = NULL,
-- status = 'INFORMACION_INCOMPLETA' y usar «Reincorporar» desde la app (postea el cargo de nuevo).

BEGIN;

CREATE TEMP TABLE _ocultar ON COMMIT DROP AS
SELECT c.id, c.status, c.excluded_reason
  FROM recon_case c
 WHERE (c.shipment_number IS NULL OR btrim(c.shipment_number) = '')
   AND (c.excluded_reason IS NULL OR c.excluded_reason = 'Venta de prueba (sin ENVL)')
   AND c.batch_id IS NULL AND c.closed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM recon_receipt_application a WHERE a.case_id = c.id AND a.status = 'ACTIVE');

-- Reversa de cada CARGO del libro (misma llave que usa la app: REV-<id del cargo>)
INSERT INTO recon_ledger_entry (case_id, entry_type, amount, reverses_entry_id, idempotency_key, note, created_by)
SELECT e.case_id, 'REVERSA', e.amount, e.id, 'REV-' || e.id,
       'Reversa de CARGO: Caso excluido: PRUEBA: venta de prueba (sin ENVL)', 8
  FROM recon_ledger_entry e
  JOIN _ocultar x ON x.id = e.case_id
 WHERE e.entry_type = 'CARGO'
   AND NOT EXISTS (SELECT 1 FROM recon_ledger_entry r WHERE r.idempotency_key = 'REV-' || e.id);

-- Bitácora (solo para los que se excluyen ahora, no para los que ya estaban excluidos con el motivo anterior)
INSERT INTO recon_event (case_id, event_type, from_status, to_status, payload, actor_id)
SELECT x.id, 'EXCLUIDO', x.status, 'EXCLUIDO', '{"motivo":"PRUEBA: venta de prueba (sin ENVL)"}', 8
  FROM _ocultar x
 WHERE x.excluded_reason IS NULL;

UPDATE recon_case c
   SET status = 'EXCLUIDO',
       excluded_reason = 'PRUEBA: venta de prueba (sin ENVL)',
       problem_type = NULL,
       pending_balance = 0,
       difference = 0,
       updated_at = now()
  FROM _ocultar x
 WHERE x.id = c.id;

-- Revisión: en prod debe dar 24 casos ocultos y 0 sin ENVL visibles
SELECT count(*) AS casos_ocultos, coalesce(sum(expected_amount), 0) AS monto_que_sale
  FROM recon_case WHERE excluded_reason LIKE 'PRUEBA:%';
SELECT count(*) AS sin_envl_visibles
  FROM recon_case WHERE (shipment_number IS NULL OR btrim(shipment_number) = '')
   AND (excluded_reason IS NULL OR excluded_reason NOT LIKE 'PRUEBA:%');

COMMIT;
