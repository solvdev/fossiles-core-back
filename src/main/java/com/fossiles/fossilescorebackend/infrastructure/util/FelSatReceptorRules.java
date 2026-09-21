package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.util.Locale;
import java.util.Set;

/**
 * Reglas SAT/INFILE de receptor.
 * 2.2.4: NCRE/NDEB no pueden ir a Consumidor Final.
 * La anulación de FACT/FCAM no usa esa restricción ni una ventana de 1 día.
 */
public final class FelSatReceptorRules {

    public static final Set<String> FACTURA_TYPES = Set.of("FACT", "FCAM");

    public static final Set<String> NOTE_TYPES = Set.of("NCRE", "NDEB");

    private FelSatReceptorRules() {
    }

    public static boolean isConsumidorFinal(String taxId) {
        String raw = normalizeTaxId(taxId);
        return raw.isBlank() || "CF".equals(raw) || "C/F".equals(raw);
    }

    public static String normalizeTaxId(String taxId) {
        if (taxId == null) {
            return "";
        }
        return taxId.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isFacturaType(String documentType) {
        String type = normalizeDocumentType(documentType);
        return FACTURA_TYPES.contains(type);
    }

    public static boolean isNotaCreditoDebito(String documentType) {
        String type = normalizeDocumentType(documentType);
        return NOTE_TYPES.contains(type);
    }

    public static String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return "";
        }
        return documentType.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isDirectFelVoidAllowed(TaxInvoiceEntity invoice) {
        if (invoice == null || !"CERTIFIED".equalsIgnoreCase(safe(invoice.getStatus()))) {
            return false;
        }
        return invoice.getFelUuid() != null && !invoice.getFelUuid().isBlank();
    }

    /**
     * SAT 2.2.4: NCRE/NDEB no pueden usar receptor CF.
     */
    public static void assertCreditDebitReceptorAllowed(String documentType, String taxId)
            throws BusinessException {
        if (!isNotaCreditoDebito(documentType)) {
            return;
        }
        if (isConsumidorFinal(taxId)) {
            throw new BusinessException(
                    "SAT (validación 2.2.4): las Notas de Crédito (NCRE) y Débito (NDEB) "
                            + "no pueden emitirse con receptor Consumidor Final (CF). "
                            + "Identifique al receptor con NIT, CUI o ID Extranjero."
            );
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
