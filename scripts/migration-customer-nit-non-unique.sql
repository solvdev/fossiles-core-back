-- Permitir varios clientes con el mismo NIT (mismo dueño / distintos negocios).
-- La unicidad de negocio sigue en legacy_code (clave de cliente).
-- Ejecutar manualmente en PostgreSQL antes del deploy (ddl-auto=validate).

ALTER TABLE customer
    DROP CONSTRAINT IF EXISTS customer_nit_key;

DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'customer'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) ILIKE '%nit%'
      AND pg_get_constraintdef(oid) NOT ILIKE '%legacy%'
    LIMIT 1;
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customer DROP CONSTRAINT %I', cname);
    END IF;
END $$;

DROP INDEX IF EXISTS customer_nit_key;
DROP INDEX IF EXISTS uq_customer_nit;

CREATE INDEX IF NOT EXISTS idx_customer_nit ON customer (nit);

COMMENT ON COLUMN customer.nit IS
    'NIT de facturación; puede repetirse entre clientes (negocios distintos del mismo dueño).';
