-- Número de hoja de envío (papel) en ventas POS Entrecueros.

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS shipping_sheet_number VARCHAR(40);
