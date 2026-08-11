package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.FelXmlEscaper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class FelAnulacionXmlBuilder {

    /** GT_AnulacionDocumento-0.1.0.xsd (SAT) — distinto al namespace 0.2.0 de GTDocumento/FACT. */
    private static final String DTE_NS = "http://www.sat.gob.gt/dte/fel/0.1.0";
    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter FEL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final FelEmissionProperties properties;

    public String buildUnsignedAnulacionXml(
            String felUuid,
            String nitEmisor,
            String receptorId,
            String fechaEmisionDocumentoAnular,
            String reason
    ) {
        return buildUnsignedAnulacionXml(
                felUuid, nitEmisor, receptorId, fechaEmisionDocumentoAnular, reason, ZonedDateTime.now(GUATEMALA));
    }

    /**
     * @param annulmentAt fecha/hora de anulación (zona Guatemala); inyectable en tests.
     */
    public String buildUnsignedAnulacionXml(
            String felUuid,
            String nitEmisor,
            String receptorId,
            String fechaEmisionDocumentoAnular,
            String reason,
            ZonedDateTime annulmentAt
    ) {
        ZonedDateTime now = annulmentAt != null ? annulmentAt.withZoneSameInstant(GUATEMALA) : ZonedDateTime.now(GUATEMALA);
        String fechaOriginal = fechaEmisionDocumentoAnular != null && !fechaEmisionDocumentoAnular.isBlank()
                ? fechaEmisionDocumentoAnular.trim()
                : formatFelDateTime(now);
        String fechaAnulacion = formatFelDateTime(now);
        String uuid = normalizeUuid(felUuid);
        String nit = normalizeNitEmisor(firstNonBlank(nitEmisor, properties.getNitEmisor()));
        String receptor = normalizeReceptorId(receptorId);
        String motivo = truncate(safe(reason), 255);
        if (motivo.isBlank()) {
            motivo = "Anulacion de documento";
        }

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <dte:GTAnulacionDocumento xmlns:dte="%s" Version="0.1">
                  <dte:SAT>
                    <dte:AnulacionDTE ID="DatosCertificados">
                      <dte:DatosGenerales ID="DatosAnulacion" NumeroDocumentoAAnular="%s" NITEmisor="%s" IDReceptor="%s" FechaEmisionDocumentoAnular="%s" FechaHoraAnulacion="%s" MotivoAnulacion="%s"/>
                    </dte:AnulacionDTE>
                  </dte:SAT>
                </dte:GTAnulacionDocumento>
                """.formatted(
                DTE_NS,
                FelXmlEscaper.escape(uuid),
                FelXmlEscaper.escape(nit),
                FelXmlEscaper.escape(receptor),
                fechaOriginal,
                fechaAnulacion,
                FelXmlEscaper.escape(motivo)
        );
    }

    private static String formatFelDateTime(ZonedDateTime value) {
        return value.withZoneSameInstant(GUATEMALA).format(FEL_DATE_TIME);
    }

    private static String normalizeUuid(String value) {
        return safe(value).toUpperCase();
    }

    private static String normalizeNitEmisor(String value) {
        return safe(value).replace(" ", "").replace("-", "");
    }

    private static String normalizeReceptorId(String value) {
        String raw = safe(value).toUpperCase();
        if (raw.isBlank() || "CF".equals(raw) || "C/F".equals(raw)) {
            return "CF";
        }
        return raw.replace(" ", "").replace("-", "");
    }

    private static String truncate(String value, int maxLen) {
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen).trim();
    }

    private static String firstNonBlank(String a, String b) {
        String first = safe(a);
        if (!first.isBlank()) {
            return first;
        }
        return safe(b);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
