-- Corte a producción FEL: desactiva "Modo piloto POS" (pos_test_mode) en los puntos de venta
-- ya listos para facturar en real con las credenciales de producción INFILE.
-- El resto de kioskos permanece en pos_test_mode=true (sandbox) hasta su propio corte.

-- Villa Lobos (kiosko piloto original, ya validado)
UPDATE locations
SET pos_test_mode = false
WHERE code = 'INT_VLOBOS'
   OR UPPER(name) LIKE '%INTERPLAZA%VILLALOBOS%';

-- CUEROGLAM central (bodega, ventas online, facturación manual — establecimiento FEL 1)
UPDATE locations
SET pos_test_mode = false
WHERE fel_establishment_code = '1';

-- Verificación
SELECT id, code, name, categoria, fel_establishment_code, pos_test_mode
FROM locations
ORDER BY pos_test_mode DESC, fel_establishment_code NULLS LAST, name;
