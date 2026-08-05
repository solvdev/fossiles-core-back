-- Permite registrar cambios libres cuando no existe una venta POS original.
ALTER TABLE kiosk_exchange_slip
    ALTER COLUMN original_sale_id DROP NOT NULL;

ALTER TABLE kiosk_exchange_slip
    ALTER COLUMN original_sale_item_id DROP NOT NULL;
