package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import java.time.LocalDateTime;

/**
 * Filtros del listado y resumen de Facturas FEL.
 *
 * @param textPattern  búsqueda general (número interno, NIT, cliente, serie, número o UUID FEL), ya en %MAYÚSCULAS%
 * @param includeTests si false, excluye documentos de prueba (serie INFILE "**PRUEBAS**" o ventas POS de prueba)
 */
public record TaxInvoiceSearchFilter(
        String sourceType,
        String status,
        String documentType,
        String customerTaxIdPattern,
        String internalNumberPattern,
        String textPattern,
        String certificationFilter,
        LocalDateTime from,
        LocalDateTime to,
        boolean includeTests
) {
    /** Serie que INFILE asigna a los DTE certificados en el ambiente de pruebas. */
    public static final String SANDBOX_SERIE = "**PRUEBAS**";
}
