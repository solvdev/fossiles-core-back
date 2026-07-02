package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve la fecha/hora de emisión que INFILE tiene registrada para un DTE certificado.
 * La anulación debe enviar exactamente la misma {@code FechaHoraEmision} del FACT original.
 */
public final class FelEmissionDateResolver {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter FEL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Pattern FECHA_HORA_EMISION = Pattern.compile(
            "FechaHoraEmision\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private FelEmissionDateResolver() {
    }

    /**
     * Valor literal para {@code FechaEmisionDocumentoAnular} (preferir XML certificado).
     */
    public static String resolveAnnulmentEmissionDateTime(TaxInvoiceEntity invoice) {
        String fromXml = extractEmissionDateTimeFromCertifiedXml(
                invoice != null ? invoice.getFelCertifiedXml() : null);
        if (fromXml != null) {
            return fromXml;
        }
        ZonedDateTime resolved = resolveOriginalEmission(invoice);
        return resolved.format(FEL_DATE_TIME);
    }

    public static ZonedDateTime resolveOriginalEmission(TaxInvoiceEntity invoice) {
        if (invoice == null) {
            return ZonedDateTime.now(GUATEMALA);
        }
        String fromXml = extractEmissionDateTimeFromCertifiedXml(invoice.getFelCertifiedXml());
        if (fromXml != null) {
            ZonedDateTime parsed = parseFelDateTime(fromXml);
            if (parsed != null) {
                return parsed;
            }
        }
        if (invoice.getIssuedAt() != null) {
            return invoice.getIssuedAt().atZone(GUATEMALA);
        }
        return ZonedDateTime.now(GUATEMALA);
    }

    static String extractEmissionDateTimeFromCertifiedXml(String certifiedXml) {
        if (certifiedXml == null || certifiedXml.isBlank()) {
            return null;
        }
        Matcher matcher = FECHA_HORA_EMISION.matcher(certifiedXml);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1).trim();
        return raw.isBlank() ? null : raw;
    }

    static ZonedDateTime parseFelDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return ZonedDateTime.parse(raw, FEL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            LocalDateTime local = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return local.atZone(GUATEMALA);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
