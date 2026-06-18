-- XML del DTE certificado devuelto por INFILE (campo xml_certificado decodificado)
ALTER TABLE tax_invoice
    ADD COLUMN IF NOT EXISTS fel_certified_xml TEXT;

COMMENT ON COLUMN tax_invoice.fel_certified_xml IS 'XML del DTE autorizado por el certificador FEL (respuesta xml_certificado)';
