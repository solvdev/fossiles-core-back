package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Reglas SAT/INFILE para receptor CF (anulación directa FACT/FCAM) y NCRE/NDEB (no CF).
 * Referencia: Reglas y Validaciones FEL 2.12.6 y 2.2.4.
 */
public final class FelSatReceptorRules {

    public static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");

    /** Tipos de factura a los que aplica la ventana de anulación directa CF. */
    public static final Set<String> FACTURA_TYPES = Set.of("FACT", "FCAM");

    /** Notas FEL (emisión deshabilitada por flag; regla CF lista para cuando se activen). */
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

    /**
     * Anulación directa CF: FechaAnulacion = día de emisión o el día siguiente (GT).
     */
    public static boolean isWithinDirectAnnulmentWindow(LocalDate emissionDateGt, LocalDate annulmentDateGt) {
        if (emissionDateGt == null || annulmentDateGt == null) {
            return false;
        }
        return !annulmentDateGt.isBefore(emissionDateGt)
                && !annulmentDateGt.isAfter(emissionDateGt.plusDays(1));
    }

    public static LocalDate directAnnulmentDeadlineDate(LocalDate emissionDateGt) {
        if (emissionDateGt == null) {
            return null;
        }
        return emissionDateGt.plusDays(1);
    }

    public static LocalDate resolveEmissionDateGt(TaxInvoiceEntity invoice) {
        ZonedDateTime emission = FelEmissionDateResolver.resolveOriginalEmission(invoice);
        return emission.withZoneSameInstant(GUATEMALA).toLocalDate();
    }

    public static boolean isDirectFelVoidAllowed(TaxInvoiceEntity invoice, LocalDate todayGt) {
        if (invoice == null || !"CERTIFIED".equalsIgnoreCase(safe(invoice.getStatus()))) {
            return false;
        }
        if (invoice.getFelUuid() == null || invoice.getFelUuid().isBlank()) {
            return false;
        }
        if (!isConsumidorFinal(invoice.getCustomerTaxId())) {
            return true;
        }
        if (!isFacturaType(invoice.getDocumentType())) {
            return true;
        }
        LocalDate emission = resolveEmissionDateGt(invoice);
        LocalDate today = todayGt != null ? todayGt : GuatemalaDateTime.today();
        return isWithinDirectAnnulmentWindow(emission, today);
    }

    /**
     * Bloquea transmitir anulación directa CF fuera de plazo.
     */
    public static void assertDirectAnnulmentAllowed(TaxInvoiceEntity invoice, LocalDate todayGt)
            throws BusinessException {
        if (invoice == null) {
            return;
        }
        if (!isConsumidorFinal(invoice.getCustomerTaxId()) || !isFacturaType(invoice.getDocumentType())) {
            return;
        }
        LocalDate emission = resolveEmissionDateGt(invoice);
        LocalDate today = todayGt != null ? todayGt : GuatemalaDateTime.today();
        if (isWithinDirectAnnulmentWindow(emission, today)) {
            return;
        }
        LocalDate deadline = directAnnulmentDeadlineDate(emission);
        throw new BusinessException(
                "SAT: la anulación directa de facturas a Consumidor Final (CF) solo puede hacerse "
                        + "el día de emisión (" + emission + ") o el día siguiente (hasta " + deadline + "). "
                        + "Fuera de ese plazo la solicitud pasa a anulación extemporánea ante la SAT "
                        + "y no se envía desde este sistema."
        );
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
