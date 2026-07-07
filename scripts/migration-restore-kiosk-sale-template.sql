-- Plantilla para restaurar UNA venta POS eliminada por error.
--
-- RECOMENDADO: usar la API (genera items, tax_invoice borrador y respeta correlativos):
--   POST /api/kiosk-pos/sales/restore
--
-- Ejemplo JSON (ajusta IDs, montos y productos):
-- {
--   "saleNumber": "POS-20260407-0015",
--   "kioskLocationId": 12,
--   "saleDate": "2026-04-07",
--   "soldAt": "2026-04-07T15:30:00",
--   "customerTaxId": "CF",
--   "customerName": "CONSUMIDOR FINAL",
--   "paymentMethod": "EFECTIVO",
--   "amountReceived": 530.00,
--   "subtotal": 530.00,
--   "discountAmount": 0,
--   "totalAmount": 530.00,
--   "createTaxInvoiceDraft": true,
--   "felUuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
--   "felSerie": "ABC123",
--   "felNumero": "1234567890",
--   "felStatus": "CERTIFIED",
--   "felCertifiedAt": "2026-04-07T15:31:00",
--   "items": [
--     { "productId": 101, "colorId": 5, "size": "M", "quantity": 1, "unitPrice": 530.00, "lineTotal": 530.00 }
--   ]
-- }
--
-- La restauración NO mueve inventario (la venta física ya ocurrió).
-- Después puedes completar/corregir FEL en Contabilidad si hace falta.

-- Verificar que el número no exista ya en el kiosko
SELECT id, sale_number, sale_date, total_amount, status
FROM kiosk_sale
WHERE kiosk_location_id = :KIOSK_LOCATION_ID
  AND UPPER(sale_number) = UPPER(:SALE_NUMBER);

-- Secuencia del día (opcional: asegurar que no regrese un correlativo menor)
SELECT * FROM kiosk_sale_sequence WHERE sale_date = :SALE_DATE;

-- Ventas del kiosko sin tax_invoice (para backfill posterior)
SELECT ks.id, ks.sale_number
FROM kiosk_sale ks
WHERE ks.kiosk_location_id = :KIOSK_LOCATION_ID
  AND NOT EXISTS (
      SELECT 1 FROM tax_invoice ti
      WHERE ti.source_type = 'KIOSK_SALE' AND ti.source_id = ks.id
  );
