-- Sincroniza enlaces y datos FEL entre kiosk_sale y tax_invoice.
--
-- CLAVE DE EMPAREJAMIENTO POS:
--   kiosk_sale.sale_number = tax_invoice.fel_transaction_id
--   (así lo genera KioskSaleInvoiceMapper.buildTransactionId)
--
-- Relaciones que deben quedar alineadas:
--   tax_invoice.source_type = 'KIOSK_SALE'
--   tax_invoice.source_id   = kiosk_sale.id
--   kiosk_sale.invoice_id   = tax_invoice.id
--
-- Ejecutar en orden: PREVIEW → UPDATE → VERIFICACIÓN.
-- Recomendado: correr primero solo los SELECT y revisar filas antes del UPDATE.

-- ---------------------------------------------------------------------------
-- 1) PREVIEW: facturas enlazables por sale_number = fel_transaction_id
--    pero con source_id / invoice_id desalineados
-- ---------------------------------------------------------------------------
SELECT
    ks.id              AS sale_id,
    ks.sale_number,
    ks.kiosk_location_id,
    ks.invoice_id      AS sale_invoice_id,
    ti.id              AS tax_invoice_id,
    ti.source_type,
    ti.source_id,
    ti.fel_transaction_id,
    ti.internal_number,
    ti.status          AS invoice_status,
    ti.fel_uuid,
    ks.fel_status      AS sale_fel_status,
    ks.fel_uuid        AS sale_fel_uuid
FROM kiosk_sale ks
JOIN tax_invoice ti
  ON TRIM(ti.fel_transaction_id) = TRIM(ks.sale_number)
WHERE ks.sale_number IS NOT NULL
  AND TRIM(ks.sale_number) <> ''
  AND (
      ti.source_type <> 'KIOSK_SALE'
      OR ti.source_id IS DISTINCT FROM ks.id
      OR ks.invoice_id IS DISTINCT FROM ti.id
  )
ORDER BY ks.sold_at DESC, ks.id DESC;

-- ---------------------------------------------------------------------------
-- 2) PREVIEW: ventas con tax_invoice por source_id pero sin fel_transaction_id
-- ---------------------------------------------------------------------------
SELECT
    ks.id              AS sale_id,
    ks.sale_number,
    ti.id              AS tax_invoice_id,
    ti.fel_transaction_id,
    ti.internal_number,
    ti.status
FROM kiosk_sale ks
JOIN tax_invoice ti
  ON ti.source_type = 'KIOSK_SALE'
 AND ti.source_id = ks.id
WHERE ks.sale_number IS NOT NULL
  AND TRIM(ks.sale_number) <> ''
  AND (
      ti.fel_transaction_id IS NULL
      OR TRIM(ti.fel_transaction_id) = ''
      OR TRIM(ti.fel_transaction_id) <> TRIM(ks.sale_number)
  )
ORDER BY ks.sold_at DESC;

-- ---------------------------------------------------------------------------
-- 3) PREVIEW: ventas con invoice_id huérfano o apuntando a otra factura
-- ---------------------------------------------------------------------------
SELECT
    ks.id,
    ks.sale_number,
    ks.invoice_id,
    ti_by_id.id        AS invoice_by_id,
    ti_by_src.id       AS invoice_by_source,
    ti_by_txn.id       AS invoice_by_txn
FROM kiosk_sale ks
LEFT JOIN tax_invoice ti_by_id
  ON ti_by_id.id = ks.invoice_id
LEFT JOIN tax_invoice ti_by_src
  ON ti_by_src.source_type = 'KIOSK_SALE'
 AND ti_by_src.source_id = ks.id
LEFT JOIN tax_invoice ti_by_txn
  ON TRIM(ti_by_txn.fel_transaction_id) = TRIM(ks.sale_number)
WHERE ks.invoice_id IS NULL
   OR ti_by_id.id IS NULL
   OR ks.invoice_id IS DISTINCT FROM COALESCE(ti_by_src.id, ti_by_txn.id)
ORDER BY ks.sold_at DESC
LIMIT 200;

-- ---------------------------------------------------------------------------
-- 4) UPDATE: alinear tax_invoice ← kiosk_sale (match por sale_number)
-- ---------------------------------------------------------------------------
UPDATE tax_invoice ti
SET source_type = 'KIOSK_SALE',
    source_id = ks.id,
    fel_transaction_id = TRIM(ks.sale_number),
    updated_at = NOW()
FROM kiosk_sale ks
WHERE ks.sale_number IS NOT NULL
  AND TRIM(ks.sale_number) <> ''
  AND TRIM(ti.fel_transaction_id) = TRIM(ks.sale_number)
  AND (
      ti.source_type <> 'KIOSK_SALE'
      OR ti.source_id IS DISTINCT FROM ks.id
      OR ti.fel_transaction_id IS DISTINCT FROM TRIM(ks.sale_number)
  );

-- ---------------------------------------------------------------------------
-- 5) UPDATE: fijar fel_transaction_id cuando ya hay source_id pero falta txn id
-- ---------------------------------------------------------------------------
UPDATE tax_invoice ti
SET fel_transaction_id = TRIM(ks.sale_number),
    updated_at = NOW()
FROM kiosk_sale ks
WHERE ti.source_type = 'KIOSK_SALE'
  AND ti.source_id = ks.id
  AND ks.sale_number IS NOT NULL
  AND TRIM(ks.sale_number) <> ''
  AND (
      ti.fel_transaction_id IS NULL
      OR TRIM(ti.fel_transaction_id) = ''
      OR TRIM(ti.fel_transaction_id) <> TRIM(ks.sale_number)
  );

-- ---------------------------------------------------------------------------
-- 6) UPDATE: kiosk_sale.invoice_id ← tax_invoice (por source o por txn id)
-- ---------------------------------------------------------------------------
UPDATE kiosk_sale ks
SET invoice_id = ti.id
FROM tax_invoice ti
WHERE (
        ti.source_type = 'KIOSK_SALE'
    AND ti.source_id = ks.id
    )
   OR (
        ks.sale_number IS NOT NULL
    AND TRIM(ks.sale_number) <> ''
    AND TRIM(ti.fel_transaction_id) = TRIM(ks.sale_number)
    )
  AND (ks.invoice_id IS NULL OR ks.invoice_id <> ti.id);

-- ---------------------------------------------------------------------------
-- 7) UPDATE: datos de cliente / montos en tax_invoice desde la venta POS
--    (solo borradores / sin firmar; no toca CERTIFIED ni VOID)
-- ---------------------------------------------------------------------------
UPDATE tax_invoice ti
SET customer_tax_id = ks.customer_tax_id,
    customer_name   = ks.customer_name,
    address         = ks.address,
    phone           = ks.phone,
    email           = ks.email,
    subtotal        = COALESCE(ks.subtotal, ti.subtotal),
    discount_amount = COALESCE(ks.discount_amount, ti.discount_amount),
    total_amount    = COALESCE(ks.total_amount, ti.total_amount),
    issued_at       = COALESCE(ti.issued_at, ks.sold_at),
    updated_at      = NOW()
FROM kiosk_sale ks
WHERE ti.source_type = 'KIOSK_SALE'
  AND ti.source_id = ks.id
  AND ti.status NOT IN ('CERTIFIED', 'VOID');

-- ---------------------------------------------------------------------------
-- 8) UPDATE: FEL certificado tax_invoice → kiosk_sale
-- ---------------------------------------------------------------------------
UPDATE kiosk_sale ks
SET fel_status       = ti.status,
    fel_uuid         = ti.fel_uuid,
    fel_serie        = ti.fel_serie,
    fel_numero       = ti.fel_numero,
    fel_error        = NULL,
    fel_certified_at = ti.fel_certified_at
FROM tax_invoice ti
WHERE ks.invoice_id = ti.id
  AND ti.status = 'CERTIFIED'
  AND (
      ks.fel_status IS DISTINCT FROM ti.status
      OR ks.fel_uuid IS DISTINCT FROM ti.fel_uuid
      OR ks.fel_serie IS DISTINCT FROM ti.fel_serie
      OR ks.fel_numero IS DISTINCT FROM ti.fel_numero
      OR ks.fel_certified_at IS DISTINCT FROM ti.fel_certified_at
  );

-- ---------------------------------------------------------------------------
-- 9) UPDATE: FEL en tax_invoice ← kiosk_sale (cuando la venta ya tenía FEL
--    y la factura sigue sin UUID / sin firmar)
-- ---------------------------------------------------------------------------
UPDATE tax_invoice ti
SET status           = CASE
                          WHEN ks.fel_status IN ('CERTIFIED', 'FAILED', 'SKIPPED', 'DRAFT', 'VOID')
                              THEN ks.fel_status
                          ELSE ti.status
                       END,
    fel_uuid         = COALESCE(NULLIF(TRIM(ti.fel_uuid), ''), ks.fel_uuid),
    fel_serie        = COALESCE(NULLIF(TRIM(ti.fel_serie), ''), ks.fel_serie),
    fel_numero       = COALESCE(NULLIF(TRIM(ti.fel_numero), ''), ks.fel_numero),
    fel_certified_at = COALESCE(ti.fel_certified_at, ks.fel_certified_at),
    fel_error        = CASE
                          WHEN COALESCE(NULLIF(TRIM(ti.fel_uuid), ''), ks.fel_uuid) IS NOT NULL
                              THEN NULL
                          ELSE COALESCE(ti.fel_error, ks.fel_error)
                       END,
    issued_at        = COALESCE(ti.issued_at, ks.fel_certified_at, ks.sold_at),
    updated_at       = NOW()
FROM kiosk_sale ks
WHERE ti.source_type = 'KIOSK_SALE'
  AND ti.source_id = ks.id
  AND ti.status NOT IN ('CERTIFIED', 'VOID')
  AND (
      (ti.fel_uuid IS NULL OR TRIM(ti.fel_uuid) = '') AND ks.fel_uuid IS NOT NULL
      OR (ti.fel_transaction_id IS NULL OR TRIM(ti.fel_transaction_id) = '')
         AND ks.sale_number IS NOT NULL
  );

-- ---------------------------------------------------------------------------
-- 10) VERIFICACIÓN final
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS ventas_sin_enlace
FROM kiosk_sale ks
WHERE NOT EXISTS (
    SELECT 1
    FROM tax_invoice ti
    WHERE (ti.source_type = 'KIOSK_SALE' AND ti.source_id = ks.id)
       OR (ks.sale_number IS NOT NULL
           AND TRIM(ti.fel_transaction_id) = TRIM(ks.sale_number))
);

SELECT COUNT(*) AS ventas_con_invoice_id_desalineado
FROM kiosk_sale ks
JOIN tax_invoice ti
  ON ti.source_type = 'KIOSK_SALE'
 AND ti.source_id = ks.id
WHERE ks.invoice_id IS DISTINCT FROM ti.id;

SELECT COUNT(*) AS facturas_pos_sin_fel_transaction_id
FROM tax_invoice ti
JOIN kiosk_sale ks
  ON ti.source_type = 'KIOSK_SALE'
 AND ti.source_id = ks.id
WHERE ks.sale_number IS NOT NULL
  AND TRIM(ks.sale_number) <> ''
  AND (
      ti.fel_transaction_id IS NULL
      OR TRIM(ti.fel_transaction_id) = ''
      OR TRIM(ti.fel_transaction_id) <> TRIM(ks.sale_number)
  );
