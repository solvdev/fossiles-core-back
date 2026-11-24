package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceEstablishmentReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceEstablishmentReportResponse.EstablishmentOption;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceEstablishmentReportResponse.Row;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceSearchFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reporte de facturas FEL por establecimiento: una fila por documento certificado o anulado, sin detalle,
 * con las mismas columnas del reporte SAT (serie, número, adendas, establecimiento, tipo, receptor, estado,
 * fecha y gran total).
 *
 * <p>El establecimiento se toma del {@code CodigoEstablecimiento} del XML certificado (lo que firmó el SAT);
 * si no hay XML, se deduce de la serie del número interno (ej. "D2-17" → serie D2).
 */
@Service
@RequiredArgsConstructor
public class TaxInvoiceEstablishmentReportService {

    private static final int ID_CHUNK = 1000;
    private static final String UNKNOWN_LABEL = "SIN ESTABLECIMIENTO";

    private final TaxInvoiceRepository taxInvoiceRepository;
    private final LocationRepository locationRepository;

    /** Establecimientos FEL configurados en Ubicaciones (código y nombre), ordenados por código. */
    @Transactional(readOnly = true)
    public List<EstablishmentOption> listEstablishments() {
        Map<String, String> namesByCode = establishmentNamesByCode(locationRepository.findAll());
        return namesByCode.entrySet().stream()
                .map(entry -> EstablishmentOption.builder()
                        .code(entry.getKey())
                        .name(entry.getValue())
                        .label(label(entry.getKey(), entry.getValue()))
                        .build())
                .sorted(Comparator.comparing((EstablishmentOption o) -> codeSortKey(o.getCode()))
                        .thenComparing(EstablishmentOption::getCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaxInvoiceEstablishmentReportResponse report(
            LocalDate fromDate,
            LocalDate toDate,
            Collection<String> establishmentCodes,
            boolean includeTests
    ) {
        TaxInvoiceSearchFilter filter = new TaxInvoiceSearchFilter(
                null, null, null, null, null, null, null,
                fromDate != null ? fromDate.atStartOfDay() : null,
                toDate != null ? toDate.atTime(LocalTime.MAX) : null,
                includeTests);
        List<TaxInvoiceEntity> invoices = taxInvoiceRepository.search(filter).stream()
                .filter(invoice -> isEmitted(invoice) || isVoided(invoice))
                .toList();

        List<LocationEntity> locations = locationRepository.findAll();
        Map<String, String> namesByCode = establishmentNamesByCode(locations);
        Map<String, String> codeBySeries = establishmentCodeBySeries(locations);
        Map<Long, String> codeByInvoiceId = certifiedEstablishmentCodes(invoices);

        Set<String> wanted = new HashSet<>();
        if (establishmentCodes != null) {
            establishmentCodes.stream()
                    .filter(code -> code != null && !code.isBlank())
                    .forEach(code -> wanted.add(code.trim()));
        }

        List<Row> rows = new ArrayList<>();
        for (TaxInvoiceEntity invoice : invoices) {
            String code = codeByInvoiceId.get(invoice.getId());
            if (code == null) {
                code = codeBySeries.get(seriesOf(invoice.getInternalNumber()));
            }
            if (!wanted.isEmpty() && (code == null || !wanted.contains(code))) {
                continue;
            }
            rows.add(toRow(invoice, code, namesByCode));
        }
        rows.sort(Comparator
                .comparing((Row r) -> codeSortKey(r.getEstablishmentCode()))
                .thenComparing(Row::getIssuedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Row::getId));
        return TaxInvoiceEstablishmentReportResponse.builder().rows(rows).build();
    }

    private static Row toRow(TaxInvoiceEntity invoice, String code, Map<String, String> namesByCode) {
        String documentType = safe(invoice.getDocumentType()).toUpperCase(Locale.ROOT);
        return Row.builder()
                .id(invoice.getId())
                .establishmentCode(code == null ? "" : code)
                .establishmentLabel(code == null ? UNKNOWN_LABEL : label(code, namesByCode.get(code)))
                .felSerie(safe(invoice.getFelSerie()))
                .felNumero(safe(invoice.getFelNumero()))
                .internalNumber(safe(invoice.getInternalNumber()))
                .documentType(documentType)
                .documentTypeLabel(documentTypeLabel(documentType))
                .customerTaxId(safe(invoice.getCustomerTaxId()))
                .customerName(safe(invoice.getCustomerName()))
                .status(isVoided(invoice) ? "ANULADO" : "EMITIDO")
                .issuedAt(invoice.getIssuedAt() != null
                        ? invoice.getIssuedAt()
                        : invoice.getFelCertifiedAt() != null ? invoice.getFelCertifiedAt() : invoice.getCreatedAt())
                .totalAmount(invoice.getTotalAmount())
                .sourceType(invoice.getSourceType())
                .build();
    }

    /** Código de establecimiento del XML certificado, extraído en la base (sin traer el XML a memoria). */
    private Map<Long, String> certifiedEstablishmentCodes(List<TaxInvoiceEntity> invoices) {
        Map<Long, String> result = new HashMap<>();
        List<Long> ids = invoices.stream().map(TaxInvoiceEntity::getId).toList();
        for (int start = 0; start < ids.size(); start += ID_CHUNK) {
            List<Object[]> chunk = taxInvoiceRepository.findCertifiedEstablishmentCodes(
                    ids.subList(start, Math.min(start + ID_CHUNK, ids.size())));
            for (Object[] pair : chunk) {
                if (pair[0] != null && pair[1] != null && !pair[1].toString().isBlank()) {
                    result.put(((Number) pair[0]).longValue(), pair[1].toString().trim());
                }
            }
        }
        return result;
    }

    private static Map<String, String> establishmentNamesByCode(List<LocationEntity> locations) {
        Map<String, String> names = new LinkedHashMap<>();
        for (LocationEntity location : locations) {
            String code = safe(location.getFelEstablishmentCode());
            if (code.isEmpty()) {
                continue;
            }
            String name = safe(location.getFelEstablishmentName());
            if (!names.containsKey(code) || (names.get(code).isEmpty() && !name.isEmpty())) {
                names.put(code, name);
            }
        }
        return names;
    }

    private static Map<String, String> establishmentCodeBySeries(List<LocationEntity> locations) {
        Map<String, String> bySeries = new HashMap<>();
        for (LocationEntity location : locations) {
            String series = safe(location.getInternalSeriesCode()).toUpperCase(Locale.ROOT);
            String code = safe(location.getFelEstablishmentCode());
            if (!series.isEmpty() && !code.isEmpty()) {
                bySeries.putIfAbsent(series, code);
            }
        }
        return bySeries;
    }

    /** "D2-17" → "D2"; vacío si no tiene el formato serie-correlativo. */
    private static String seriesOf(String internalNumber) {
        String value = safe(internalNumber).toUpperCase(Locale.ROOT);
        int dash = value.lastIndexOf('-');
        return dash > 0 ? value.substring(0, dash) : "";
    }

    private static String label(String code, String name) {
        String cleanName = safe(name).toUpperCase(Locale.ROOT);
        return cleanName.isEmpty() ? code : code + " - " + cleanName;
    }

    private static String documentTypeLabel(String documentType) {
        return switch (documentType) {
            case "FCAM" -> "FACTURA CAMBIARIA";
            case "NCRE" -> "NOTA DE CRÉDITO";
            case "NDEB" -> "NOTA DE DÉBITO";
            default -> "FACTURA";
        };
    }

    /** Código numérico primero (1, 41, 44…); lo no numérico o vacío al final. */
    private static long codeSortKey(String code) {
        try {
            return Long.parseLong(safe(code));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isEmitted(TaxInvoiceEntity invoice) {
        return "CERTIFIED".equalsIgnoreCase(safe(invoice.getStatus()));
    }

    private static boolean isVoided(TaxInvoiceEntity invoice) {
        return "VOID".equalsIgnoreCase(safe(invoice.getStatus()));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
