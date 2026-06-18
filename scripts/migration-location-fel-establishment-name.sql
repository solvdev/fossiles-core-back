-- Nombre oficial del establecimiento FEL (catálogo INFILE/SAT)
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS fel_establishment_name VARCHAR(255);

COMMENT ON COLUMN locations.fel_establishment_name IS 'Nombre del establecimiento autorizado en FEL (ej. CUEROGLAM INTERPLAZA VILLALOBOS)';
