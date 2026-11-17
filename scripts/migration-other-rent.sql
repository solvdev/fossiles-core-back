-- Contabilidad > Rentas: rentas que no son de un kiosco (oficina, bodega u otra).
-- NO altera kiosk_rent ni ninguna otra tabla. No crea permisos: usa CONTABILIDAD.RENTAS.VER / EDITAR.
--
-- ddl-auto=validate: hay que dejar este script en COMMIT y correrlo ANTES de desplegar el back.
-- Esta copia termina en ROLLBACK para revisarla en DataGrip (sin meta-comandos de psql).
-- Cuando los cambios sean los esperados, cambie la última instrucción a COMMIT y vuelva a correrla.
--
-- El índice único es parcial (solo rentas activas) y usa lower(btrim(name)). No se declara en la
-- entidad JPA: H2 no lo crea igual y Hibernate validate no lo exige. El servicio también lo valida.

BEGIN;

CREATE TABLE IF NOT EXISTS other_rent (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    category        VARCHAR(20)  NOT NULL,
    landlord_name   VARCHAR(200),
    monthly_amount  NUMERIC(12, 2),
    payment_day     INT,
    contract_start  DATE,
    contract_end    DATE,
    note            VARCHAR(500),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    source          VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP,
    CONSTRAINT chk_other_rent_category CHECK (category IN ('OFICINA', 'BODEGA', 'OTRO')),
    CONSTRAINT chk_other_rent_day CHECK (payment_day IS NULL OR payment_day BETWEEN 1 AND 31),
    CONSTRAINT chk_other_rent_amount CHECK (monthly_amount IS NULL OR monthly_amount >= 0),
    CONSTRAINT chk_other_rent_dates CHECK (contract_end IS NULL OR contract_start IS NULL OR contract_end >= contract_start),
    CONSTRAINT chk_other_rent_source CHECK (source IN ('MANUAL', 'EXCEL'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_other_rent_active_category_name
    ON other_rent (category, lower(btrim(name)))
    WHERE active;

ROLLBACK; -- cambiar a COMMIT antes de mezclar: el back valida el esquema al arrancar

-- REVERSE (solo esta tabla y su índice; no toca nada más):
-- DROP INDEX IF EXISTS uq_other_rent_active_category_name;
-- DROP TABLE IF EXISTS other_rent;
