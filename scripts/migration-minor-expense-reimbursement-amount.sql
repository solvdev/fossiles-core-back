-- Separar monto a reembolsar (MENSAJERO) del vuelto (EMPRESA / returned_amount)
ALTER TABLE minor_expense
    ADD COLUMN IF NOT EXISTS reimbursement_amount NUMERIC(12, 2);

-- Backfill: lo que antes vivía en messenger_amount como reembolso
UPDATE minor_expense
SET reimbursement_amount = messenger_amount
WHERE initial_payment_method = 'MENSAJERO'
  AND reimbursement_amount IS NULL
  AND messenger_amount IS NOT NULL;

-- EMPRESA: no hay reembolso; asegurar returned_amount si solo había messenger_amount (vuelto legacy)
UPDATE minor_expense
SET returned_amount = COALESCE(returned_amount, messenger_amount),
    reimbursement_amount = COALESCE(reimbursement_amount, 0)
WHERE initial_payment_method = 'EMPRESA';

UPDATE minor_expense
SET reimbursement_amount = 0
WHERE reimbursement_amount IS NULL;
