-- Contabilidad > Rentas: arrendador, día de pago y vigencia del contrato de renta de cada kiosco.
-- Ejecutar ANTES de desplegar el back. Es idempotente.
--
-- El MONTO de la renta no se guarda aquí: se lee de Finanzas (kiosk_fixed_cost, categoría ALQUILER), donde ya está.

CREATE TABLE IF NOT EXISTS kiosk_rent (
    location_id     BIGINT PRIMARY KEY REFERENCES locations (id) ON DELETE CASCADE,
    landlord_name   VARCHAR(200),                       -- a quién se le paga (arrendador)
    payment_day     INT,                                -- día del mes en que se paga (1-31)
    contract_start  DATE,
    contract_end    DATE,
    note            VARCHAR(500),
    source          VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',   -- MANUAL | EXCEL
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP,
    CONSTRAINT chk_kiosk_rent_day CHECK (payment_day IS NULL OR payment_day BETWEEN 1 AND 31),
    CONSTRAINT chk_kiosk_rent_dates CHECK (contract_start IS NULL OR contract_end IS NULL OR contract_end >= contract_start)
);

-- Permisos (asignar a los roles de Contabilidad; ADMIN siempre puede). Cerrar sesión y volver a entrar.
INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.RENTAS.VER', 'Ver las rentas de los kioscos (arrendador, día de pago y contrato)', 'CONTABILIDAD', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.RENTAS.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.RENTAS.EDITAR', 'Editar y subir por Excel el arrendador, día de pago y contrato de los kioscos', 'CONTABILIDAD', 'EDITAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.RENTAS.EDITAR');
