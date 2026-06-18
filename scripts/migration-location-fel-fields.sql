-- Datos FEL por punto de venta (kiosko)
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS fel_establishment_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS fel_address_line VARCHAR(500),
    ADD COLUMN IF NOT EXISTS fel_municipio VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fel_departamento VARCHAR(255);

COMMENT ON COLUMN locations.fel_establishment_code IS 'CodigoEstablecimiento SAT/INFILE para emision FEL en este punto de venta';
COMMENT ON COLUMN locations.fel_establishment_name IS 'Nombre del establecimiento autorizado en FEL (catálogo INFILE)';
COMMENT ON COLUMN locations.fel_address_line IS 'Direccion del establecimiento para XML emisor FEL';
COMMENT ON COLUMN locations.fel_municipio IS 'Municipio emisor FEL (XML separado)';
COMMENT ON COLUMN locations.fel_departamento IS 'Departamento emisor FEL (XML separado)';
