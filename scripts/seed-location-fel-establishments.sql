-- Seed / UPDATE: códigos y nombres de establecimiento FEL (CUEROGLAM 11700874K)
-- Fuente: catálogo INFILE "Establecimientos autorizados" (credenciales prueba CUEROGLAM)
-- Ejecutar después de migration-location-fel-fields.sql y migration-location-fel-establishment-name.sql
--
-- Reglas:
--   • NO kiosko (bodega, online, etc.) → establecimiento 1 (CUEROGLAM central)
--   • KIOSKO → un UPDATE por establecimiento, del más específico al más general
--   • Solo asigna si fel_establishment_code sigue NULL (primer match gana)

CREATE TEMP TABLE tmp_fel_establishments (
    fel_code         VARCHAR(10) PRIMARY KEY,
    fel_name         VARCHAR(255) NOT NULL,
    fel_address_line VARCHAR(500) NOT NULL,
    fel_municipio    VARCHAR(255) NOT NULL,
    fel_departamento VARCHAR(255) NOT NULL
);

INSERT INTO tmp_fel_establishments (fel_code, fel_name, fel_address_line, fel_municipio, fel_departamento) VALUES
('1',  'CUEROGLAM',                        'BOULEVARD TULAMTZU OFIBODEGAS VALLE DEL SOL, BODEGA NO. 3 ZONA 4', 'Guatemala', 'GUATEMALA'),
('2',  'CUEROGLAM ATANASIO',               'CALZADA ATANASIO TZUL 51-57 CENTRO COMERCIAL PLAZA ATANASIO TZUL KIOSCO J ZONA 12', 'Guatemala', 'GUATEMALA'),
('3',  'CUEROGLAM CHIMALTENANGO',          'DIAGONAL UNO 1-75 CENTRO COMERCIAL LA PRADERA CHIMALTENANGO PRIMER NIVEL KIOSKO A-07 ZONA 6', 'Chimaltenango', 'CHIMALTENANGO'),
('4',  'CUEROGLAM CHIQUIMULA',             'CARRETERA CA 10 CENTRO COMERCIAL PRADERA CHIQUIMULA KM. 167.5 RUTA A ESQUIPULAS KIOSCO 23', 'Chiquimula', 'CHIQUIMULA'),
('5',  'CUEROGLAM INTERPLAZA XELA',        'CARRETERA QUE CONDUCE A SAN MARCOS CENTRO COMERCIAL INTERPLAZA XELA KM. 205 KIOSKO NO. 203, 2DO NIVEL', 'Quetzaltenango', 'QUETZALTENANGO'),
('6',  'CUEROGLAM JALAPA',                 'CALLE 1ERA 2-66 CENTRO COMERCIAL PLAZA SAN FRANCISCO BARRIO SAN FRANCISCO, 2DO NIVEL KIOSKO NO.7 ZONA 2', 'Jalapa', 'JALAPA'),
('7',  'CUEROGLAM MAJADAS ONCE',           '27 AVENIDA 6-40 CENTRO COMERCIAL MAJADAS ONCE KIOSKO 5 PRIMER NIVEL ZONA 11', 'Guatemala', 'GUATEMALA'),
('8',  'CUEROGLAM METROCENTRO',            '16-20 CENTRO COMERCIAL METROCENTRO VILLA NUEVA CERO CALLE KIOSKO K-9 2DO NIVEL ZONA 10', 'San Miguel Petapa', 'GUATEMALA'),
('9',  'CUEROGLAM MIRAFLORES II',          '21 AVENIDA 4-32 CENTRO COMERCIAL MIRAFLORES 2DO NIVEL FASE 3 KIOSKO K-9 ZONA 11', 'Guatemala', 'GUATEMALA'),
('10', 'CUEROGLAM JUTIAPA',                'CENTRO COMERCIAL METROPLAZA JUTIAPA CARRETERA INTERAMERICANA A LA ALTURA KM. 116 LOCAL 122', 'Jutiapa', 'JUTIAPA'),
('11', 'CUEROGLAM PRADERA VISTARES',       'DIAGONAL 19 36-01 CENTRO COMERCIAL LA PRADERA VISTARES KIOSKO N1-K05 PRIMER NIVEL ZONA 12', 'Guatemala', 'GUATEMALA'),
('12', 'CUEROGLAM SAN LUCAS',              'CARRETERA INTERAMERICANA CENTRO COMERCIAL SAN LUCAS KILOMETRO 29.5 KIOSCO A', 'San Lucas Sacatepéquez', 'SACATEPÉQUEZ'),
('13', 'CUEROGLAM SANKRIS',                'BOULEVARD SAN CRISTOBAL 6-72 CENTRO COMERCIAL SANKRIS 3 ERA CALLE SECTOR A-3, 2DO NIVEL KIOSCO C2-K5 ZONA 8', 'Mixco', 'GUATEMALA'),
('14', 'CUEROGLAM SANTALU',                'CARRETERA AL PACIFICO CENTRO COMERCIAL SANTALÚ KILOMETRO 80.5 PRIMER NIVEL, KIOSCO K-2', 'Santa Lucía Cotzumalguapa', 'ESCUINTLA'),
('15', 'CUEROGLAM ZONA 4',                 '6 AVENIDA 0-60 GRAN CENTRO COMERCIAL ZONA 4, KIOSKO KB-7 ZONA 4', 'Guatemala', 'GUATEMALA'),
('16', 'CUEROGLAM NARANJO',                '23 CALLE CONDADO EL NARANJO 10-00 CENTRO COMERCIAL NARANJO MALL PRIMER NIVEL KIOSCO K-14 ZONA 4', 'Mixco', 'GUATEMALA'),
('17', 'CUEROGLAM ESKALA',                 'CARRETERA ROOSEVELT CENTRO COMERCIAL ESKALA KM. 13.8 2DO NIVEL K-22 ZONA 3', 'Mixco', 'GUATEMALA'),
('18', 'CUEROGLAM RUS',                    'CALZADA ROOSEVELT CENTRO COMERCIAL RUS 12-76 Y 12-80 PRIMER NIVEL, KIOSCO K1-09 ZONA 7', 'Guatemala', 'GUATEMALA'),
('19', 'CUEROGLAM ANDARIA',                'KILÓMETRO 55.50 CARRETERA INTERAMERICANA CENTRO COMERCIAL ANDARIA KIOSCO K-5 ZONA 4', 'Chimaltenango', 'CHIMALTENANGO'),
('20', 'CUEROGLAM COATEPEQUE',             '6 CALLE LOTIFICACION LA FELICIDAD 12-124 CENTRO COMERCIAL LA TRINIDAD KIOSCO 22 ZONA 1', 'Coatepeque', 'QUETZALTENANGO'),
('21', 'CUEROGLAM INTERPLAZA ESCUINTLA',   'KILÓMETRO 60 DE LA AUTOPISTA QUE CONDUCE DE ESCUINTLA A PALIN CENTRO COMERCIAL INTERPLAZA ESCUINTLA KIOSCO NO.33 SEGUNDO NIVEL', 'Escuintla', 'ESCUINTLA'),
('22', 'CUEROGLAM PLAZA CEMACO',           'BOULEVARD LOS PROCERES 4-96 CENTRO COMERCIAL PLAZA CEMACO KIOSCO K-19 ZONA 10', 'Guatemala', 'GUATEMALA'),
('23', 'CUEROGLAM PRADERA CONCEPCION',     'KILÓMETRO 15.50 CARRETERA A EL SALVADOR CENTRO COMERCIAL PRADERA CONCEPCION FINCA CONCEPCION, 2DO NIVEL KIOSCO 75', 'Santa Catarina Pinula', 'GUATEMALA'),
('24', 'CUEROGLAM PRADERA ESCUINTLA',      '1 AVENIDA 1-40 CENTRO COMERCIAL LA PRADERA ESCUINTLA KIOSCO A-2B PRIMER NIVEL ZONA 3', 'Escuintla', 'ESCUINTLA'),
('25', 'CUEROGLAM PRADERA XELA',           'AVENIDA LAS AMERICAS 7-12 CENTRO COMERCIAL PRADERA XELA KIOSCO K-8 PRIMER NIVEL ZONA 3', 'Quetzaltenango', 'QUETZALTENANGO'),
('26', 'CUEROGLAM PORTALES',               'RUTA A LA CARRETERA AL ATLANTICO 3-20 CENTRO COMERCIAL PORTALES CA-9 NORTE, 2DO NIVEL KIOSCO K-25 ZONA 17', 'Guatemala', 'GUATEMALA'),
('27', 'CUEROGLAM RETALHULEU',             '2 AVENIDA 4-40 EXPANSION C.C. LA TRINIDAD RETALHULEU, KIOSCO KM-1 ZONA 5', 'Retalhuleu', 'RETALHULEU'),
('28', 'CUEROGLAM SANTA CLARA',            'CARRETERA AL PACIFICO BARCENAS CENTRO COMERCIAL SANTA CLARA KM. 17.5, KIOSCO K 205 ZONA 3', 'Villa Nueva', 'GUATEMALA'),
('29', 'CUEROGLAM EL FRUTAL',              'BOULEVARD EL FRUTAL 14-00 COMPLEJO COMERCIAL EL FRUTAL, KIOSCO NO. 6, PRIMER NIVEL ZONA 5', 'Villa Nueva', 'GUATEMALA'),
('30', 'CUEROGLAM COBAN',                  '1 CALLE 15-20 CENTRO COMERCIAL PLAZA MAGDALENA KIOSCO 25 ZONA 2', 'Cobán', 'ALTA VERAPAZ'),
('31', 'CUEROGLAM TIKAL FUTURA',           'CALZADA ROOSEVELT 22-43 CENTRO COMERCIAL TIKAL FUTURA KIOSCO 143-A-SV PRIMER NIVEL ZONA 11', 'Guatemala', 'GUATEMALA'),
('32', 'CUEROGLAM MAZATENANGO',            'CENTRO COMERCIAL PLAZA AMERICAS KM. 158 DE LA CARRETERA QUE CONDUCE DE LA CIUDAD DE GUATEMALA, HACIA MAZATENANGO, KIOSCO 16', 'Mazatenango', 'SUCHITEPÉQUEZ'),
('33', 'CUEROGLAM METRONORTE',             'CARRETERA AL ATLANTICO CENTRO COMERCIAL METRONORTE KM. 5, KIOSCO K-525 1ER NIVEL ZONA 17', 'Guatemala', 'GUATEMALA'),
('34', 'CUEROGLAM PACIFIC CENTER',         'CALZADA AGUILAR BATRES 32-10 CENTRO COMERCIAL PACIFIC CENTER PRIMER NIVEL, KIOSCO 502 ZONA 11', 'Guatemala', 'GUATEMALA'),
('35', 'CUEROGLAM SANTA AMELIA',           '24 CALLE BOULEVARD CENTRO MEDICO MILITAR 12-05 CENTRO COMERCIAL PLAZA SANTA AMELIA KIOSCO NO. 11 ZONA 16', 'Guatemala', 'GUATEMALA'),
('36', 'CUEROGLAM PERI ROOSEVELT',         'CALZADA ROOSEVELT 25-50 CENTRO COMERCIAL PERI ROOSEVELT 1ER NIVEL KIOSCO 12 ZONA 7', 'Guatemala', 'GUATEMALA'),
('37', 'CUEROGLAM MIRAFLORES',             '21 AVENIDA 4-32 CENTRO COMERCIAL MIRAFLORES 2DO NIVEL KIOSCO KT-124 ZONA 11', 'Guatemala', 'GUATEMALA'),
('38', 'ENTRECUEROS',                      '20 CALLE 3-47 COMERCIAL EL PUEBLITO 4TO NIVEL LOCAL 449 ZONA 1', 'Guatemala', 'GUATEMALA'),
('39', 'CUEROGLAM UTZULEU MALL',           '19 AVENIDA 2-40 CENTRO COMERCIAL UTZULEW MALL KIOSCO 12 PRIMER NIVEL ZONA 3', 'Quetzaltenango', 'QUETZALTENANGO'),
('40', 'DECOBONSAI ML',                    'MANZANA E SECTOR 2 LOTE 17B PLANES DEL MILAGRO ZONA 0', 'Chinautla', 'GUATEMALA'),
('41', 'VILÉ STUDIO',                      'KILÓMETRO 29.50 CARRETERA INTERAMERICANA CENTRO COMERCIAL SAN LUCAS 2DO. NIVEL LOCAL 108', 'San Lucas Sacatepéquez', 'SACATEPÉQUEZ'),
('42', 'CUEROGLAM PLAZA DEL PARQUE COBAN', '1 CALLE 3-13 ZONA 1', 'Cobán', 'ALTA VERAPAZ'),
('43', 'CUEROGLAM PLAZA TELARES',          'KILÓMETRO 46 FINCA LAS VICTORIAS CENTRO COMERCIAL PLAZA TELARES LOTE 13, CARRETERA A CIUDAD VIEJA, KIOSCO 13', 'Antigua Guatemala', 'SACATEPÉQUEZ'),
('44', 'VILE ESTUDIO PUNTO ROOSEVELT',     'CALZADA ROOSEVELT 13-70 CENTRO COMERCIAL PUNTO ROOSEVELT LOCAL 104 Y 105, TERCER NIVEL ZONA 7', 'Guatemala', 'GUATEMALA'),
('45', 'CUEROGLAM LOS CELAJES QUICHE',     'CENTRO COMERCIAL LOS CELAJES LA ROCHELA, A LA ORILLA DE LA CARRETERA QUE CONDUCE A TOTONICAPAN, KIOSCO K-9 PRIMER NIVEL', 'Santa Cruz del Quiché', 'QUICHÉ'),
('46', 'CUEROGLAM INTERPLAZA VILLALOBOS',  'KILÓMETRO 13.80 CARRETERA AL PACIFICO CENTRO COMERCIAL INTERPLAZA VILLA LOBOS SEGUNDO NIVEL, KIOSCO NO. 18 ZONA 6', 'Villa Nueva', 'GUATEMALA'),
('47', 'VILE STUDIO INARA',                'RUTA 2 EDIFICIO INARA CUARTO NORTE 5-51, SEGUNDO NIVEL LOCAL L-202 ZONA 4', 'Guatemala', 'GUATEMALA');

-- Helper: normaliza texto para comparar nombres
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

-- ---------------------------------------------------------------------------
-- 0) Limpiar asignaciones FEL erróneas (re-ejecutable)
-- ---------------------------------------------------------------------------
UPDATE locations
SET
    fel_establishment_code = NULL,
    fel_establishment_name = NULL,
    fel_address_line = NULL,
    fel_municipio = NULL,
    fel_departamento = NULL;

-- ---------------------------------------------------------------------------
-- 1) NO kiosko → establecimiento 1 (ventas en línea, bodegas, etc.)
-- ---------------------------------------------------------------------------
UPDATE locations l
SET
    fel_establishment_code = f.fel_code,
    fel_establishment_name = f.fel_name,
    fel_address_line = f.fel_address_line,
    fel_municipio = f.fel_municipio,
    fel_departamento = f.fel_departamento
FROM tmp_fel_establishments f
WHERE f.fel_code = '1'
  AND UPPER(COALESCE(l.categoria, '')) NOT LIKE '%KIOSKO%'
  AND UPPER(COALESCE(l.categoria, '')) NOT LIKE '%KIOSK%';

-- ---------------------------------------------------------------------------
-- 2) Kioskos: aplicar catálogo por coincidencia de nombre (específico → general)
--    Solo si aún no tienen código asignado.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.apply_fel_to_kiosks(p_fel_code TEXT, p_match_sql TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        $sql$
        UPDATE locations l
        SET
            fel_establishment_code = f.fel_code,
            fel_establishment_name = f.fel_name,
            fel_address_line = f.fel_address_line,
            fel_municipio = f.fel_municipio,
            fel_departamento = f.fel_departamento
        FROM tmp_fel_establishments f
        WHERE f.fel_code = %L
          AND (fel_establishment_code IS NULL OR TRIM(fel_establishment_code) = '')
          AND (
              UPPER(COALESCE(l.categoria, '')) LIKE '%%KIOSKO%%'
              OR UPPER(COALESCE(l.categoria, '')) LIKE '%%KIOSK%%'
          )
          AND (%s)
        $sql$,
        p_fel_code,
        p_match_sql
    );
END;
$$;

-- Interplaza / Pradera (más específicos primero)
SELECT pg_temp.apply_fel_to_kiosks('46', $$
    pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAVILLALOBOS%'
    OR pg_temp.norm_loc(l.name) LIKE '%VILLALOBOS%'
    OR UPPER(COALESCE(l.code, '')) = 'INT_VLOBOS'
$$);
SELECT pg_temp.apply_fel_to_kiosks('21', $$
    pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAESCUINTLA%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('5', $$
    pg_temp.norm_loc(l.name) LIKE '%INTERPLAZAXELA%'
    OR (pg_temp.norm_loc(l.name) LIKE '%INTERPLAZA%' AND pg_temp.norm_loc(l.name) LIKE '%XELA%')
$$);
SELECT pg_temp.apply_fel_to_kiosks('23', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERACONCEPCION%'
    OR pg_temp.norm_loc(l.name) LIKE '%CONCEPCION%PRADERA%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('24', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERAESCUINTLA%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('11', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERAVISTARES%'
    OR pg_temp.norm_loc(l.name) LIKE '%VISTARES%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('25', $$
    pg_temp.norm_loc(l.name) LIKE '%PRADERAXELA%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('3', $$
    pg_temp.norm_loc(l.name) LIKE '%CHIMALTENANGO%'
    AND pg_temp.norm_loc(l.name) LIKE '%PRADERA%'
$$);

-- Miraflores II antes que Miraflores
SELECT pg_temp.apply_fel_to_kiosks('9', $$
    pg_temp.norm_loc(l.name) LIKE '%MIRAFLORESII%'
    OR pg_temp.norm_loc(l.name) LIKE '%MIRAFLORES2%'
    OR pg_temp.norm_loc(l.name) LIKE '%MIRAFLORESFASE%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('37', $$
    pg_temp.norm_loc(l.name) LIKE '%MIRAFLORES%'
$$);

-- VILE / INARA (estricto; INARA solo si va con VILE/STUDIO)
SELECT pg_temp.apply_fel_to_kiosks('47', $$
    pg_temp.norm_loc(l.name) LIKE '%VILESTUDIOINARA%'
    OR pg_temp.norm_loc(l.name) LIKE '%STUDIOINARA%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('44', $$
    pg_temp.norm_loc(l.name) LIKE '%PUNTOROOSEVELT%'
    OR pg_temp.norm_loc(l.name) LIKE '%VILEESTUDIOPUNTO%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('41', $$
    (pg_temp.norm_loc(l.name) LIKE '%VILESTUDIO%' OR pg_temp.norm_loc(l.name) LIKE '%VILESTUDIO%')
    AND pg_temp.norm_loc(l.name) NOT LIKE '%INARA%'
    AND pg_temp.norm_loc(l.name) NOT LIKE '%PUNTO%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('38', $$
    pg_temp.norm_loc(l.name) LIKE '%ENTRECUEROS%'
    OR pg_temp.norm_loc(l.name) LIKE '%ENTRECUEROS%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('40', $$
    pg_temp.norm_loc(l.name) LIKE '%DECOBONSAI%'
    OR pg_temp.norm_loc(l.name) LIKE '%BONSAI%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('42', $$
    pg_temp.norm_loc(l.name) LIKE '%PLAZADELPARQUE%'
    OR pg_temp.norm_loc(l.name) LIKE '%PARQUECOBAN%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('43', $$
    pg_temp.norm_loc(l.name) LIKE '%PLAZATELARES%'
    OR pg_temp.norm_loc(l.name) LIKE '%TELARES%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('45', $$
    pg_temp.norm_loc(l.name) LIKE '%CELAJES%'
    OR pg_temp.norm_loc(l.name) LIKE '%LOSCELAJES%'
$$);
SELECT pg_temp.apply_fel_to_kiosks('2',  $$ pg_temp.norm_loc(l.name) LIKE '%ATANASIO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('4',  $$ pg_temp.norm_loc(l.name) LIKE '%CHIQUIMULA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('6',  $$ pg_temp.norm_loc(l.name) LIKE '%JALAPA%' AND pg_temp.norm_loc(l.name) NOT LIKE '%JUTIAPA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('7',  $$ pg_temp.norm_loc(l.name) LIKE '%MAJADASONCE%' $$);
SELECT pg_temp.apply_fel_to_kiosks('8',  $$ pg_temp.norm_loc(l.name) LIKE '%METROCENTRO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('10', $$ pg_temp.norm_loc(l.name) LIKE '%JUTIAPA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('12', $$ pg_temp.norm_loc(l.name) LIKE '%SANLUCAS%' AND pg_temp.norm_loc(l.name) NOT LIKE '%VILESTUDIO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('13', $$ pg_temp.norm_loc(l.name) LIKE '%SANKRIS%' OR pg_temp.norm_loc(l.name) LIKE '%SANCRIS%' $$);
SELECT pg_temp.apply_fel_to_kiosks('14', $$ pg_temp.norm_loc(l.name) LIKE '%SANTALU%' $$);
SELECT pg_temp.apply_fel_to_kiosks('15', $$ pg_temp.norm_loc(l.name) LIKE '%ZONA4%' OR pg_temp.norm_loc(l.name) LIKE '%GRANCENTRO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('16', $$ pg_temp.norm_loc(l.name) LIKE '%NARANJO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('17', $$ pg_temp.norm_loc(l.name) LIKE '%ESKALA%' OR pg_temp.norm_loc(l.name) LIKE '%ESCALA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('18', $$ pg_temp.norm_loc(l.name) LIKE '%CUEROGLAMRUS%' OR pg_temp.norm_loc(l.name) LIKE '%COMERCIALRUS%' OR pg_temp.norm_loc(l.name) ~ 'RUS$' $$);
SELECT pg_temp.apply_fel_to_kiosks('19', $$ pg_temp.norm_loc(l.name) LIKE '%ANDARIA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('20', $$ pg_temp.norm_loc(l.name) LIKE '%COATEPEQUE%' $$);
SELECT pg_temp.apply_fel_to_kiosks('22', $$ pg_temp.norm_loc(l.name) LIKE '%CEMACO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('26', $$ pg_temp.norm_loc(l.name) LIKE '%PORTALES%' $$);
SELECT pg_temp.apply_fel_to_kiosks('27', $$ pg_temp.norm_loc(l.name) LIKE '%RETALHULEU%' $$);
SELECT pg_temp.apply_fel_to_kiosks('28', $$ pg_temp.norm_loc(l.name) LIKE '%SANTACLARA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('29', $$ pg_temp.norm_loc(l.name) LIKE '%FRUTAL%' $$);
SELECT pg_temp.apply_fel_to_kiosks('30', $$ pg_temp.norm_loc(l.name) LIKE '%COBAN%' AND pg_temp.norm_loc(l.name) NOT LIKE '%PARQUE%' $$);
SELECT pg_temp.apply_fel_to_kiosks('31', $$ pg_temp.norm_loc(l.name) LIKE '%TIKALFUTURA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('32', $$ pg_temp.norm_loc(l.name) LIKE '%MAZATENANGO%' $$);
SELECT pg_temp.apply_fel_to_kiosks('33', $$ pg_temp.norm_loc(l.name) LIKE '%METRONORTE%' $$);
SELECT pg_temp.apply_fel_to_kiosks('34', $$ pg_temp.norm_loc(l.name) LIKE '%PACIFICCENTER%' $$);
SELECT pg_temp.apply_fel_to_kiosks('35', $$ pg_temp.norm_loc(l.name) LIKE '%SANTAAMELIA%' $$);
SELECT pg_temp.apply_fel_to_kiosks('36', $$ pg_temp.norm_loc(l.name) LIKE '%PERIROOSEVELT%' $$);
SELECT pg_temp.apply_fel_to_kiosks('39', $$ pg_temp.norm_loc(l.name) LIKE '%UTZULEU%' OR pg_temp.norm_loc(l.name) LIKE '%UTZULEW%' $$);

-- Coincidencia exacta con nombre del catálogo INFILE
UPDATE locations l
SET
    fel_establishment_code = f.fel_code,
    fel_establishment_name = f.fel_name,
    fel_address_line = f.fel_address_line,
    fel_municipio = f.fel_municipio,
    fel_departamento = f.fel_departamento
FROM tmp_fel_establishments f
WHERE (fel_establishment_code IS NULL OR TRIM(fel_establishment_code) = '')
  AND (
      UPPER(COALESCE(l.categoria, '')) LIKE '%KIOSKO%'
      OR UPPER(COALESCE(l.categoria, '')) LIKE '%KIOSK%'
  )
  AND pg_temp.norm_loc(l.name) = pg_temp.norm_loc(f.fel_name);

-- Kioskos sin match: dejar nombre = ubicación, sin inventar código
UPDATE locations
SET fel_establishment_name = COALESCE(NULLIF(TRIM(fel_establishment_name), ''), TRIM(name))
WHERE (
    UPPER(COALESCE(categoria, '')) LIKE '%KIOSKO%'
    OR UPPER(COALESCE(categoria, '')) LIKE '%KIOSK%'
)
AND (fel_establishment_code IS NULL OR TRIM(fel_establishment_code) = '');

-- ---------------------------------------------------------------------------
-- 3) Verificación
-- ---------------------------------------------------------------------------
SELECT
    l.id,
    l.code,
    l.name AS ubicacion,
    l.categoria,
    l.fel_establishment_code AS est_fel,
    l.fel_establishment_name AS nombre_establecimiento
FROM locations l
ORDER BY
    CASE WHEN UPPER(COALESCE(l.categoria, '')) LIKE '%KIOSKO%' THEN 0 ELSE 1 END,
    NULLIF(REGEXP_REPLACE(l.fel_establishment_code, '[^0-9]', '', 'g'), '')::INT NULLS LAST,
    l.name;

SELECT COUNT(*) AS kioskos_sin_codigo_fel
FROM locations
WHERE (UPPER(COALESCE(categoria, '')) LIKE '%KIOSKO%' OR UPPER(COALESCE(categoria, '')) LIKE '%KIOSK%')
  AND (fel_establishment_code IS NULL OR TRIM(fel_establishment_code) = '');

DROP FUNCTION pg_temp.apply_fel_to_kiosks(TEXT, TEXT);
DROP FUNCTION pg_temp.norm_loc(TEXT);
DROP TABLE tmp_fel_establishments;
