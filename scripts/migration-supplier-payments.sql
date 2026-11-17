-- Contabilidad > Cuentas por pagar / Pagos a proveedores / Bancos y formas de pago.
-- Ejecutar ANTES de desplegar el back. Es idempotente.

-- Datos del proveedor para pagarle: nombre que sale en el cheque (si es distinto del comercial) y días de crédito.
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS payee_name VARCHAR(200);
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS credit_days INT NOT NULL DEFAULT 30;

-- Formato de cada cheque: tamaño del papel y dónde va cada dato (milímetros). fields_json = lista de
-- {key,on,top,side,edge,size}; side = LEFT | RIGHT (de qué lado se mide la distancia).
CREATE TABLE IF NOT EXISTS cheque_layout (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    width_mm     NUMERIC(6,1) NOT NULL DEFAULT 165,
    height_mm    NUMERIC(6,1) NOT NULL DEFAULT 70,
    font_family  VARCHAR(30)  NOT NULL DEFAULT 'TIMES',
    fields_json  TEXT         NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by   BIGINT,
    updated_at   TIMESTAMP
);

-- Cuentas con las que se paga (bancos o caja). ledger_code = cuenta contable que se acredita al pagar.
CREATE TABLE IF NOT EXISTS bank_account (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    account_number        VARCHAR(60),
    ledger_code           VARCHAR(30)  NOT NULL,
    ledger_name           VARCHAR(120),
    first_cheque_number   VARCHAR(30),                -- desde dónde empieza la chequera si aún no se usó
    shares_payroll_checks BOOLEAN      NOT NULL DEFAULT FALSE,  -- la chequera también la usa Planilla
    cheque_layout_id      BIGINT REFERENCES cheque_layout (id),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by            BIGINT,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by            BIGINT,
    updated_at            TIMESTAMP
);

-- Formas de pago configurables.
CREATE TABLE IF NOT EXISTS payment_method (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(80) NOT NULL UNIQUE,
    requires_bank      BOOLEAN NOT NULL DEFAULT TRUE,
    requires_reference BOOLEAN NOT NULL DEFAULT FALSE,   -- pide número de cheque, boleta o referencia
    prints_cheque      BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order         INT     NOT NULL DEFAULT 0,
    active             BOOLEAN NOT NULL DEFAULT TRUE
);

-- Un pago a un proveedor (puede cubrir varias órdenes del mismo proveedor).
-- status: POR_IMPRIMIR (cheque aún sin imprimir) | IMPRESO | PAGADO (sin cheque) | ANULADO
CREATE TABLE IF NOT EXISTS supplier_payment (
    id                BIGSERIAL PRIMARY KEY,
    payment_number    VARCHAR(20) UNIQUE,
    supplier_id       BIGINT NOT NULL REFERENCES supplier (id),
    payment_method_id BIGINT NOT NULL REFERENCES payment_method (id),
    bank_account_id   BIGINT REFERENCES bank_account (id),
    pay_date          DATE   NOT NULL,
    amount            NUMERIC(12,2) NOT NULL,
    payee_name        VARCHAR(200),
    reference         VARCHAR(60),                       -- número de cheque, boleta o referencia
    concept           VARCHAR(300),
    status            VARCHAR(15) NOT NULL,
    printed_at        TIMESTAMP,
    printed_by        BIGINT,
    print_count       INT NOT NULL DEFAULT 0,
    void_reason       VARCHAR(300),
    voided_at         TIMESTAMP,
    voided_by         BIGINT,
    created_by        BIGINT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_supplier_payment_status CHECK (status IN ('POR_IMPRIMIR', 'IMPRESO', 'PAGADO', 'ANULADO')),
    CONSTRAINT chk_supplier_payment_amount CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_supplier_payment_supplier ON supplier_payment (supplier_id);
CREATE INDEX IF NOT EXISTS idx_supplier_payment_status ON supplier_payment (status);

-- A qué órdenes de compra se aplica cada pago, y con cuánto.
CREATE TABLE IF NOT EXISTS supplier_payment_allocation (
    id                BIGSERIAL PRIMARY KEY,
    payment_id        BIGINT NOT NULL REFERENCES supplier_payment (id) ON DELETE CASCADE,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_order (id),
    amount            NUMERIC(12,2) NOT NULL,
    CONSTRAINT chk_supplier_payment_alloc_amount CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_supplier_payment_alloc_order ON supplier_payment_allocation (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_supplier_payment_alloc_payment ON supplier_payment_allocation (payment_id);

-- Formato inicial: el mismo de los cheques de Planilla (165 x 70 mm, Times New Roman).
INSERT INTO cheque_layout (name, width_mm, height_mm, font_family, fields_json)
SELECT 'Chequera G&T 16.5 x 7 cm (igual que Planilla)', 165, 70, 'TIMES',
       '[{"key":"DATE","on":true,"top":17,"side":"LEFT","edge":20,"size":10},'
    || '{"key":"AMOUNT","on":true,"top":25.7,"side":"RIGHT","edge":10,"size":10},'
    || '{"key":"PAYEE","on":true,"top":26.4,"side":"LEFT","edge":19.4,"size":11},'
    || '{"key":"WORDS","on":true,"top":30.5,"side":"LEFT","edge":18.3,"size":9},'
    || '{"key":"CONCEPT","on":false,"top":48,"side":"LEFT","edge":19,"size":9},'
    || '{"key":"NUMBER","on":false,"top":8,"side":"RIGHT","edge":12,"size":9},'
    || '{"key":"LEGEND","on":false,"top":40,"side":"LEFT","edge":19,"size":9}]'
WHERE NOT EXISTS (SELECT 1 FROM cheque_layout);

INSERT INTO bank_account (name, ledger_code, ledger_name, shares_payroll_checks, cheque_layout_id)
SELECT 'Banco G&T Continental', '1.1.1.02', 'Bancos', TRUE, (SELECT MIN(id) FROM cheque_layout)
WHERE NOT EXISTS (SELECT 1 FROM bank_account);

INSERT INTO payment_method (name, requires_bank, requires_reference, prints_cheque, sort_order)
SELECT v.name, v.requires_bank, v.requires_reference, v.prints_cheque, v.sort_order
FROM (VALUES ('Cheque', TRUE, TRUE, TRUE, 1),
             ('Transferencia', TRUE, TRUE, FALSE, 2),
             ('Efectivo', FALSE, FALSE, FALSE, 3),
             ('Depósito', TRUE, TRUE, FALSE, 4)) AS v(name, requires_bank, requires_reference, prints_cheque, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM payment_method);

-- Permisos (asignar a los roles de Contabilidad; ADMIN siempre puede). Cerrar sesión y volver a entrar.
INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.PAGOS_PROVEEDORES.VER', 'Ver cuentas por pagar y pagos a proveedores', 'CONTABILIDAD', 'VER'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.PAGOS_PROVEEDORES.VER');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.PAGOS_PROVEEDORES.PAGAR', 'Registrar, imprimir cheques y anular pagos a proveedores', 'CONTABILIDAD', 'PAGAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.PAGOS_PROVEEDORES.PAGAR');

INSERT INTO permission (code, description, module, action)
SELECT 'CONTABILIDAD.PAGOS_PROVEEDORES.CONFIGURAR', 'Configurar bancos, formas de pago y formato de cheques', 'CONTABILIDAD', 'CONFIGURAR'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'CONTABILIDAD.PAGOS_PROVEEDORES.CONFIGURAR');
