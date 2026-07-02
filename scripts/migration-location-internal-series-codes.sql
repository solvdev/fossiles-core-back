-- Asigna el código de serie de control interno (locations.internal_series_code) por kiosco,
-- según catálogo operativo CUEROGLAM (columna SERIE / FACTURA LOCAL).
-- El número que ve contabilidad y la factura FEL es tax_invoice.internal_number = "<SERIE>-<correlativo>"
-- (ej. A45-241), incluido en la Adenda del XML como <NumeroControlInterno>.
--
-- Este script es autocontenido: crea columna/tabla si aún no existen.
-- Para fijar desde qué correlativo continuar por serie:
--   last_number = el número ya usado (solo la parte después del guión).
--   Ej.: si la última factura fue A45-13 → last_number = 13 → la siguiente será A45-14.
--
--   INSERT INTO location_internal_number_sequence (series_code, last_number)
--   VALUES ('A45', 13)
--   ON CONFLICT (series_code) DO UPDATE SET last_number = EXCLUDED.last_number;

-- Esquema base (idempotente; también en migration-location-internal-number.sql)
ALTER TABLE kiosk_sale DROP COLUMN IF EXISTS internal_series;
DROP TABLE IF EXISTS kiosk_internal_series_sequence;

ALTER TABLE kiosk_sale
    ADD COLUMN IF NOT EXISTS card_auth_number VARCHAR(40),
    ADD COLUMN IF NOT EXISTS card_last4 VARCHAR(4);

ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS internal_series_code VARCHAR(10);

CREATE TABLE IF NOT EXISTS location_internal_number_sequence (
    series_code VARCHAR(10) PRIMARY KEY,
    last_number INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE tax_invoice
    ADD COLUMN IF NOT EXISTS internal_number VARCHAR(40);

CREATE OR REPLACE FUNCTION pg_temp.norm_loc(txt TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT UPPER(
        REGEXP_REPLACE(
            REGEXP_REPLACE(
                REGEXP_REPLACE(COALESCE(txt, ''), '[ÁÀÄÂ]', 'A', 'g'),
                '[ÉÈËÊ]', 'E', 'g'
            ),
            '[^A-Z0-9]', '', 'g'
        )
    );
$$;

CREATE OR REPLACE FUNCTION pg_temp.apply_internal_series(p_series_code TEXT, p_match_sql TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        $sql$
        UPDATE locations l
        SET internal_series_code = %L
        WHERE (%s)
        $sql$,
        p_series_code,
        p_match_sql
    );
END;
$$;

-- CUEROGLAM central / bodega (ventas online, factura cambiaria en catálogo → serie B)
UPDATE locations
SET internal_series_code = 'B'
WHERE fel_establishment_code = '1'
   OR (UPPER(COALESCE(categoria, '')) NOT LIKE '%KIOSKO%'
       AND UPPER(COALESCE(categoria, '')) NOT LIKE '%KIOSK%'
       AND pg_temp.norm_loc(name) LIKE '%CUEROGLAM%'
       AND pg_temp.norm_loc(name) NOT LIKE '%STUDIO%');

-- Kioskos: del más específico al más general (misma lógica que seed FEL)
SELECT pg_temp.apply_internal_series('A45', $$
    pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAVILLALOBOS%'
    OR pg_temp.norm_loc(l.name) LIKE '%VILLALOBOS%'
    OR UPPER(COALESCE(l.code, '')) = 'INT_VLOBOS'
$$);
SELECT pg_temp.apply_internal_series('A34', $$ pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAESCUINTLA%' $$);
SELECT pg_temp.apply_internal_series('A10', $$
    pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAXELA%'
    OR (pg_temp.norm_loc(l.name) LIKE '%INTERPLAZA%' AND pg_temp.norm_loc(l.name) LIKE '%XELA%')
$$);
SELECT pg_temp.apply_internal_series('A39', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERACONCEPCION%'
$$);
SELECT pg_temp.apply_internal_series('A7', $$ pg_temp.norm_loc(l.name) LIKE '%PRADERAESCUINTLA%' $$);
SELECT pg_temp.apply_internal_series('A26', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERAVISTARES%'
    OR pg_temp.norm_loc(l.name) LIKE '%VISTARES%'
$$);
SELECT pg_temp.apply_internal_series('A3', $$
    pg_temp.norm_loc(l.name) LIKE '%CHIMALTENANGO%'
    AND pg_temp.norm_loc(l.name) LIKE '%PRADERA%'
$$);
SELECT pg_temp.apply_internal_series('A15', $$
    pg_temp.norm_loc(l.name) LIKE '%MIRAFLORESII%'
    OR pg_temp.norm_loc(l.name) LIKE '%MIRAFLORES2%'
$$);
SELECT pg_temp.apply_internal_series('D3', $$
    pg_temp.norm_loc(l.name) LIKE '%VILESTUDIOINARA%'
    OR pg_temp.norm_loc(l.name) LIKE '%STUDIOINARA%'
$$);
SELECT pg_temp.apply_internal_series('D2', $$
    pg_temp.norm_loc(l.name) LIKE '%PUNTOROOSEVELT%'
    OR pg_temp.norm_loc(l.name) LIKE '%VILEESTUDIOPUNTO%'
$$);
SELECT pg_temp.apply_internal_series('D1', $$
    (pg_temp.norm_loc(l.name) LIKE '%VILESTUDIO%' OR pg_temp.norm_loc(l.name) LIKE '%VILESTUDIO%')
    AND pg_temp.norm_loc(l.name) NOT LIKE '%INARA%'
    AND pg_temp.norm_loc(l.name) NOT LIKE '%PUNTO%'
$$);
SELECT pg_temp.apply_internal_series('A38', $$ pg_temp.norm_loc(l.name) LIKE '%ENTRECUEROS%' $$);
SELECT pg_temp.apply_internal_series('A43', $$
    pg_temp.norm_loc(l.name) LIKE '%PLAZATELARES%'
    OR pg_temp.norm_loc(l.name) LIKE '%TELARES%'
$$);
SELECT pg_temp.apply_internal_series('A44', $$
    pg_temp.norm_loc(l.name) LIKE '%CELAJES%'
    OR pg_temp.norm_loc(l.name) LIKE '%LOSCELAJES%'
$$);
SELECT pg_temp.apply_internal_series('A1',  $$ pg_temp.norm_loc(l.name) LIKE '%ATANASIO%' $$);
SELECT pg_temp.apply_internal_series('A2',  $$ pg_temp.norm_loc(l.name) LIKE '%CEMACO%' $$);
SELECT pg_temp.apply_internal_series('A4',  $$ pg_temp.norm_loc(l.name) LIKE '%CHIQUIMULA%' $$);
SELECT pg_temp.apply_internal_series('A5',  $$ pg_temp.norm_loc(l.name) LIKE '%COATEPEQUE%' $$);
SELECT pg_temp.apply_internal_series('A6',  $$ pg_temp.norm_loc(l.name) LIKE '%COBAN%' AND pg_temp.norm_loc(l.name) NOT LIKE '%PARQUE%' $$);
SELECT pg_temp.apply_internal_series('A8',  $$ pg_temp.norm_loc(l.name) LIKE '%ESKALA%' OR pg_temp.norm_loc(l.name) LIKE '%ESCALA%' $$);
SELECT pg_temp.apply_internal_series('A9',  $$ pg_temp.norm_loc(l.name) LIKE '%FRUTAL%' $$);
SELECT pg_temp.apply_internal_series('A36', $$ pg_temp.norm_loc(l.name) LIKE '%JALAPA%' AND pg_temp.norm_loc(l.name) NOT LIKE '%JUTIAPA%' $$);
SELECT pg_temp.apply_internal_series('A11', $$ pg_temp.norm_loc(l.name) LIKE '%JUTIAPA%' $$);
SELECT pg_temp.apply_internal_series('A12', $$ pg_temp.norm_loc(l.name) LIKE '%MAZATENANGO%' $$);
SELECT pg_temp.apply_internal_series('A13', $$ pg_temp.norm_loc(l.name) LIKE '%METRONORTE%' $$);
SELECT pg_temp.apply_internal_series('A40', $$ pg_temp.norm_loc(l.name) LIKE '%METROCENTRO%' $$);
SELECT pg_temp.apply_internal_series('A16', $$ pg_temp.norm_loc(l.name) LIKE '%NARANJO%' $$);
SELECT pg_temp.apply_internal_series('A17', $$ pg_temp.norm_loc(l.name) LIKE '%PACIFICCENTER%' $$);
SELECT pg_temp.apply_internal_series('A18', $$ pg_temp.norm_loc(l.name) LIKE '%PERIROOSEVELT%' $$);
SELECT pg_temp.apply_internal_series('A19', $$ pg_temp.norm_loc(l.name) LIKE '%PORTALES%' $$);
SELECT pg_temp.apply_internal_series('A20', $$ pg_temp.norm_loc(l.name) LIKE '%RETALHULEU%' $$);
SELECT pg_temp.apply_internal_series('A21', $$
    pg_temp.norm_loc(l.name) LIKE '%SANLUCAS%'
    AND pg_temp.norm_loc(l.name) NOT LIKE '%VILESTUDIO%'
$$);
SELECT pg_temp.apply_internal_series('A22', $$ pg_temp.norm_loc(l.name) LIKE '%SANKRIS%' OR pg_temp.norm_loc(l.name) LIKE '%SANCRIS%' $$);
SELECT pg_temp.apply_internal_series('A23', $$ pg_temp.norm_loc(l.name) LIKE '%SANTACLARA%' $$);
SELECT pg_temp.apply_internal_series('A35', $$ pg_temp.norm_loc(l.name) LIKE '%SANTALU%' $$);
SELECT pg_temp.apply_internal_series('A24', $$ pg_temp.norm_loc(l.name) LIKE '%UTZULEU%' OR pg_temp.norm_loc(l.name) LIKE '%UTZULEW%' $$);
SELECT pg_temp.apply_internal_series('A25', $$ pg_temp.norm_loc(l.name) LIKE '%ZONA4%' OR pg_temp.norm_loc(l.name) LIKE '%GRANCENTRO%' $$);
SELECT pg_temp.apply_internal_series('A29', $$ pg_temp.norm_loc(l.name) LIKE '%TIKALFUTURA%' $$);
SELECT pg_temp.apply_internal_series('A32', $$ pg_temp.norm_loc(l.name) LIKE '%ANDARIA%' $$);
SELECT pg_temp.apply_internal_series('A37', $$
    pg_temp.norm_loc(l.name) LIKE '%CUEROGLAMRUS%'
    OR pg_temp.norm_loc(l.name) LIKE '%COMERCIALRUS%'
    OR pg_temp.norm_loc(l.name) ~ 'RUS$'
$$);

-- ---------------------------------------------------------------------------
-- Correlativos iniciales (último número ya emitido por serie; la siguiente factura = +1)
-- Completar un bloque por cada kiosko cuando contabilidad confirme el corte.
-- ---------------------------------------------------------------------------
INSERT INTO location_internal_number_sequence (series_code, last_number)
VALUES ('A45', 13)  -- Villa Lobos: última A45-13 → siguiente A45-14
ON CONFLICT (series_code) DO UPDATE SET last_number = EXCLUDED.last_number;

-- Plantilla para otras series (descomentar y ajustar):
-- INSERT INTO location_internal_number_sequence (series_code, last_number) VALUES ('A1', 0) ON CONFLICT (series_code) DO UPDATE SET last_number = EXCLUDED.last_number;
-- INSERT INTO location_internal_number_sequence (series_code, last_number) VALUES ('B', 0) ON CONFLICT (series_code) DO UPDATE SET last_number = EXCLUDED.last_number;

-- Verificación
SELECT id, code, name, categoria, fel_establishment_code, internal_series_code
FROM locations
ORDER BY (internal_series_code IS NULL) DESC, internal_series_code NULLS LAST, name;

SELECT series_code, last_number,
       series_code || '-' || (last_number + 1) AS proximo_numero_interno
FROM location_internal_number_sequence
ORDER BY series_code;

DROP FUNCTION pg_temp.apply_internal_series(TEXT, TEXT);
DROP FUNCTION pg_temp.norm_loc(TEXT);
