package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reglas de negocio de Notas de Crédito (NCRE) y Débito (NDEB) FEL sobre una factura certificada.
 * Saldo de la factura = total + NDEB certificadas − NCRE certificadas.
 * SAT además rechaza una NCRE cuyo total supere el GranTotal del documento origen.
 */
public final class FelCreditDebitNoteRules {

    private static final Pattern CODIGO_ESTABLECIMIENTO = Pattern.compile(
            "CodigoEstablecimiento\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private FelCreditDebitNoteRules() {
    }

    /** La factura puede recibir notas: FACT/FCAM certificada con UUID, serie y número, receptor ≠ CF. */
    public static void assertOriginAllowsNotes(TaxInvoiceEntity origin) throws BusinessException {
        if (origin == null) {
            throw new BusinessException("No se encontró la factura origen.");
        }
        if (origin.getOriginInvoiceId() != null || !FelSatReceptorRules.isFacturaType(origin.getDocumentType())) {
            throw new BusinessException("Solo se pueden emitir notas sobre facturas (FACT/FCAM).");
        }
        if (!"CERTIFIED".equalsIgnoreCase(safe(origin.getStatus()))) {
            throw new BusinessException("La factura origen debe estar certificada y vigente.");
        }
        if (safe(origin.getFelUuid()).isBlank()
                || safe(origin.getFelSerie()).isBlank()
                || safe(origin.getFelNumero()).isBlank()) {
            throw new BusinessException("La factura origen no tiene UUID, serie o número FEL.");
        }
        if (FelSatReceptorRules.isConsumidorFinal(origin.getCustomerTaxId())) {
            throw new BusinessException(
                    "SAT (validación 2.2.4): no se pueden emitir notas de crédito/débito "
                            + "sobre una factura a Consumidor Final (CF).");
        }
    }

    /** SAT 3.7.1: el receptor de la nota debe ser el mismo de la factura origen. */
    public static void assertSameReceptor(String originTaxId, String noteTaxId) throws BusinessException {
        if (!normalizeReceptor(originTaxId).equals(normalizeReceptor(noteTaxId))) {
            throw new BusinessException("El receptor de la nota debe ser el mismo NIT de la factura origen.");
        }
    }

    /**
     * Saldo vigente de la factura considerando solo notas certificadas.
     *
     * @param excludeNoteId nota que se está reintentando (no cuenta contra su propio saldo)
     */
    public static BigDecimal remainingBalance(
            BigDecimal originTotal, List<TaxInvoiceEntity> notes, Long excludeNoteId) {
        BigDecimal balance = nz(originTotal);
        if (notes != null) {
            for (TaxInvoiceEntity note : notes) {
                if (note == null
                        || Objects.equals(note.getId(), excludeNoteId)
                        || !"CERTIFIED".equalsIgnoreCase(safe(note.getStatus()))) {
                    continue;
                }
                String type = FelSatReceptorRules.normalizeDocumentType(note.getDocumentType());
                if ("NCRE".equals(type)) {
                    balance = balance.subtract(nz(note.getTotalAmount()));
                } else if ("NDEB".equals(type)) {
                    balance = balance.add(nz(note.getTotalAmount()));
                }
            }
        }
        return balance.setScale(2, RoundingMode.HALF_UP);
    }

    /** NCRE: no puede pasar el saldo ni el total de la factura. NDEB: solo monto positivo. */
    public static void assertNoteAmountAllowed(
            String documentType, BigDecimal noteTotal, BigDecimal originTotal, BigDecimal remainingBalance)
            throws BusinessException {
        BigDecimal amount = nz(noteTotal).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El total de la nota debe ser mayor a cero.");
        }
        if (!"NCRE".equals(FelSatReceptorRules.normalizeDocumentType(documentType))) {
            return;
        }
        BigDecimal total = nz(originTotal).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(total) > 0) {
            throw new BusinessException(
                    "La nota de crédito (Q " + amount.toPlainString() + ") supera el total de la factura (Q "
                            + total.toPlainString() + ").");
        }
        BigDecimal balance = nz(remainingBalance).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException(
                    "La nota de crédito (Q " + amount.toPlainString() + ") supera el saldo de la factura (Q "
                            + balance.toPlainString() + ") considerando las notas ya certificadas.");
        }
    }

    /** Hay al menos una nota certificada (vigente) ligada a la factura. */
    public static boolean hasActiveNotes(List<TaxInvoiceEntity> notes) {
        return notes != null && notes.stream()
                .anyMatch(note -> note != null && "CERTIFIED".equalsIgnoreCase(safe(note.getStatus())));
    }

    /** {@code CodigoEstablecimiento} del emisor en el XML certificado de la factura origen; null si no hay. */
    public static String extractEstablishmentCode(String certifiedXml) {
        if (certifiedXml == null || certifiedXml.isBlank()) {
            return null;
        }
        Matcher matcher = CODIGO_ESTABLECIMIENTO.matcher(certifiedXml);
        if (!matcher.find()) {
            return null;
        }
        String code = matcher.group(1).trim();
        return code.isEmpty() ? null : code;
    }

    private static String normalizeReceptor(String taxId) {
        return FelSatReceptorRules.normalizeTaxId(taxId).replace(" ", "").replace("-", "");
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
