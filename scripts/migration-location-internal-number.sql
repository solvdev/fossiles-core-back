-- Reemplaza el intento anterior de columna "internal_series" en kiosk_sale: el correlativo de
-- control interno vive en tax_invoice.internal_number y solo se genera cuando SÍ se emite una
-- factura (con NIT o CF), nunca para ventas que no facturan.
ALTER TABLE kiosk_sale DROP COLUMN IF EXISTS internal_series;
DROP TABLE IF EXISTS kiosk_internal_series_sequence;

-- Datos de tarjeta en POS (autorización + últimos 4 dígitos).
ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS card_auth_number VARCHAR(40),
    ADD COLUMN IF NOT EXISTS card_last4 VARCHAR(4);

-- Código de serie de control interno por ubicación (ej. "A1", "B", "D1"). NO es la serie que
-- asigna el certificador FEL; es únicamente para control interno propio y se incluye como
-- Adenda en el XML de cada factura emitida desde esa ubicación.
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS internal_series_code VARCHAR(10);

-- Correlativo del número de control interno, uno por código de serie.
CREATE TABLE IF NOT EXISTS location_internal_number_sequence (
    series_code VARCHAR(10) PRIMARY KEY,
    last_number INTEGER NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- PENDIENTE: completar por cada ubicación con su código de serie real y el
-- correlativo desde el que debe continuar (para no repetir números ya usados
-- en el sistema anterior). El número final que verá el cliente y el certificador
-- (como Adenda) es "<CODIGO_SERIE>-<correlativo>", ej. "A1-241".
--
-- Reemplaza <LOCATION_ID>, <CODIGO_SERIE> y <ULTIMO_NUMERO_YA_USADO> y descomenta
-- un bloque por cada ubicación:
--
-- UPDATE locations SET internal_series_code = '<CODIGO_SERIE>' WHERE id = <LOCATION_ID>;
-- INSERT INTO location_internal_number_sequence (series_code, last_number)
-- VALUES ('<CODIGO_SERIE>', <ULTIMO_NUMERO_YA_USADO>)
-- ON CONFLICT (series_code) DO UPDATE SET last_number = EXCLUDED.last_number;

-- Verificación: ubicaciones sin código de serie asignado todavía.
SELECT id, code, name, fel_establishment_code, internal_series_code
FROM locations
ORDER BY (internal_series_code IS NULL) DESC, name;
