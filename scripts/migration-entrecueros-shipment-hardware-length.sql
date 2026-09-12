-- Entre Cueros: SINTETICO:TOMMY HILFIGER no cabe en varchar(20).

ALTER TABLE product_shipment_detail
    ALTER COLUMN hardware_condition TYPE VARCHAR(40);

ALTER TABLE kiosk_exchange_slip_given_item
    ALTER COLUMN hardware_condition TYPE VARCHAR(40);

ALTER TABLE kiosk_exchange_slip
    ALTER COLUMN given_hardware_condition TYPE VARCHAR(40);

COMMENT ON COLUMN product_shipment_detail.hardware_condition IS
    'NUEVO | VIEJO, PARA (NINO/DAMA) o marca / SINTETICO:MARCA en Entre Cueros.';
