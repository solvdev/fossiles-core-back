ALTER TABLE internal_shipment_request
    ADD COLUMN IF NOT EXISTS employee_id BIGINT NULL;

ALTER TABLE internal_shipment_request
    DROP CONSTRAINT IF EXISTS fk_internal_shipment_request_employee;

ALTER TABLE internal_shipment_request
    ADD CONSTRAINT fk_internal_shipment_request_employee
        FOREIGN KEY (employee_id) REFERENCES employee (id);

CREATE INDEX IF NOT EXISTS idx_internal_shipment_request_employee_month
    ON internal_shipment_request (employee_id, request_type, requested_at);
