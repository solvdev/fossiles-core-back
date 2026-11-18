-- Rentas en cuentas por pagar.
-- 1) Columna payable_document.other_rent_id para las rentas que no son kiosco.
-- 2) Una renta (kind = 'RENT') tiene location_id o other_rent_id, exactamente uno.
-- 3) Corrige due_date e issue_date de las rentas de kiosco ACTIVO que no tienen pagos
--    aplicados y cuyo vencimiento no coincide con el día de pago de ese mismo mes.
--
-- Correr en producción ANTES de promover release a main: ddl-auto=validate exige other_rent_id.
-- DataGrip, sin meta-comandos de psql. Esta copia termina en ROLLBACK.
-- Revise el SELECT de diagnóstico (esas son las filas que el UPDATE va a tocar). Si está bien,
-- cambie ROLLBACK por COMMIT y vuelva a correr el script. Después de un COMMIT no lo corra otra
-- vez: el respaldo _bak_payable_document_20261117 ya existiría.
--
-- La fecha correcta es el día 1 del período más (LEAST(día de pago, días del mes) - 1).
-- El 31 en febrero cae el 28 o el 29. Las filas con alguna fila en supplier_payment_allocation
-- no se tocan, aunque el pago esté anulado.

BEGIN;

-- Respaldo de las rentas, por si hay que devolver las fechas.
CREATE TABLE _bak_payable_document_20261117 AS
    SELECT * FROM payable_document WHERE kind = 'RENT';

ALTER TABLE payable_document
    ADD COLUMN IF NOT EXISTS other_rent_id BIGINT REFERENCES other_rent (id);

ALTER TABLE payable_document DROP CONSTRAINT IF EXISTS chk_payable_document_rent_target;
ALTER TABLE payable_document ADD CONSTRAINT chk_payable_document_rent_target
    CHECK (
        kind <> 'RENT'
        OR (location_id IS NOT NULL AND other_rent_id IS NULL)
        OR (location_id IS NULL AND other_rent_id IS NOT NULL)
    );

-- Una renta activa por otra renta y mes. Las anuladas no cuentan: se pueden volver a generar.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payable_document_rent_other
    ON payable_document (other_rent_id, period)
    WHERE kind = 'RENT' AND status = 'ACTIVO' AND other_rent_id IS NOT NULL;

-- Diagnóstico, solo lectura. La lista es exactamente la que corrige el UPDATE de abajo.
SELECT
    pd.id,
    pd.location_id,
    pd.period,
    pd.due_date AS due_date_actual,
    pd.issue_date AS issue_date_actual,
    (
        (trim(pd.period) || '-01')::date
        + LEAST(
            kr.payment_day,
            EXTRACT(DAY FROM (
                ((trim(pd.period) || '-01')::date + INTERVAL '1 month') - INTERVAL '1 day'
            ))::int
        )
        - 1
    )::date AS due_date_correcta
FROM payable_document pd
JOIN kiosk_rent kr ON kr.location_id = pd.location_id
WHERE pd.kind = 'RENT'
  AND pd.status = 'ACTIVO'
  AND pd.period IS NOT NULL
  AND kr.payment_day IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM supplier_payment_allocation a WHERE a.payable_document_id = pd.id
  )
  AND pd.due_date IS DISTINCT FROM (
        (trim(pd.period) || '-01')::date
        + LEAST(
            kr.payment_day,
            EXTRACT(DAY FROM (
                ((trim(pd.period) || '-01')::date + INTERVAL '1 month') - INTERVAL '1 day'
            ))::int
        )
        - 1
  )::date;

UPDATE payable_document pd
SET due_date = corregida.due_date_correcta,
    issue_date = corregida.due_date_correcta
FROM (
    SELECT
        pd2.id,
        (
            (trim(pd2.period) || '-01')::date
            + LEAST(
                kr.payment_day,
                EXTRACT(DAY FROM (
                    ((trim(pd2.period) || '-01')::date + INTERVAL '1 month') - INTERVAL '1 day'
                ))::int
            )
            - 1
        )::date AS due_date_correcta
    FROM payable_document pd2
    JOIN kiosk_rent kr ON kr.location_id = pd2.location_id
    WHERE pd2.kind = 'RENT'
      AND pd2.status = 'ACTIVO'
      AND pd2.period IS NOT NULL
      AND kr.payment_day IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM supplier_payment_allocation a WHERE a.payable_document_id = pd2.id
      )
) corregida
WHERE pd.id = corregida.id
  AND pd.due_date IS DISTINCT FROM corregida.due_date_correcta;

ROLLBACK; -- Cambiar a COMMIT después de revisar el SELECT.

-- ---------------------------------------------------------------------------
-- Reversa (comentada). Descomentar y correr aparte si ya se hizo COMMIT y hay
-- que volver atrás: restaura las fechas desde el respaldo y quita índice,
-- restricción y columna. Los documentos de otras rentas creados después pierden
-- el vínculo other_rent_id.
-- ---------------------------------------------------------------------------
-- BEGIN;
-- UPDATE payable_document pd
-- SET due_date = b.due_date,
--     issue_date = b.issue_date
-- FROM _bak_payable_document_20261117 b
-- WHERE pd.id = b.id;
-- DROP INDEX IF EXISTS uq_payable_document_rent_other;
-- ALTER TABLE payable_document DROP CONSTRAINT IF EXISTS chk_payable_document_rent_target;
-- ALTER TABLE payable_document DROP COLUMN IF EXISTS other_rent_id;
-- DROP TABLE IF EXISTS _bak_payable_document_20261117;
-- COMMIT;
