-- Piloto: CUEROGLAM Interplaza Villa Lobos (establecimiento FEL 46)
-- Ejecutar despues de migration-location-fel-fields.sql

INSERT INTO locations (
    code,
    name,
    departamento,
    municipio,
    zona,
    categoria,
    fel_establishment_code,
    fel_establishment_name,
    fel_address_line,
    fel_municipio,
    fel_departamento,
    pos_test_mode
)
SELECT
    'INT_VLOBOS',
    'CUEROGLAM INTERPLAZA VILLALOBOS',
    'Guatemala',
    'Villa Nueva',
    '6',
    'KIOSKO',
    '46',
    'CUEROGLAM INTERPLAZA VILLALOBOS',
    'Km 13.80 Carretera al Pacifico, Centro Comercial Interplaza Villa Lobos, 2do Nivel Kiosco 18',
    'Villa Nueva',
    'Guatemala',
    true
WHERE NOT EXISTS (
    SELECT 1 FROM locations
    WHERE code = 'INT_VLOBOS'
       OR UPPER(name) LIKE '%INTERPLAZA%VILLALOBOS%'
       OR UPPER(name) LIKE '%VILLALOBOS%'
);

-- Si la ubicacion ya existia con otro codigo, actualizar datos FEL por nombre
UPDATE locations
SET
    categoria = 'KIOSKO',
    pos_test_mode = true,
    fel_establishment_code = '46',
    fel_establishment_name = 'CUEROGLAM INTERPLAZA VILLALOBOS',
    fel_address_line = COALESCE(fel_address_line,
        'Km 13.80 Carretera al Pacifico, Centro Comercial Interplaza Villa Lobos, 2do Nivel Kiosco 18'),
    fel_municipio = COALESCE(fel_municipio, 'Villa Nueva'),
    fel_departamento = COALESCE(fel_departamento, 'Guatemala'),
    departamento = COALESCE(departamento, 'Guatemala'),
    municipio = COALESCE(municipio, 'Villa Nueva'),
    zona = COALESCE(zona, '6')
WHERE UPPER(name) LIKE '%INTERPLAZA%VILLALOBOS%'
   OR code = 'INT_VLOBOS';
