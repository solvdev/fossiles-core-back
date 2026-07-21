package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelCredentials;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.FelIvaCalculator;
import com.fossiles.fossilescorebackend.infrastructure.util.FelXmlEscaper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FelFactXmlBuilder {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter FEL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final FelEmissionProperties properties;

    public String buildUnsignedXml(TaxInvoiceDocument document, FelCredentials credentials) {
        ZonedDateTime emission = document.getIssuedAt() != null
                ? document.getIssuedAt().atZone(GUATEMALA)
                : ZonedDateTime.now(GUATEMALA);
        String fechaHora = emission.format(FEL_DATE_TIME);

        String receptorId = normalizeReceptorId(document.getCustomerTaxId());
        String receptorName = normalizeReceptorName(receptorId, document.getCustomerName());
        String receptorEmail = normalizeReceptorEmail(document.getEmail());

        List<TaxInvoiceDocument.Line> documentLines = document.getLines() == null
                ? List.of()
                : document.getLines();

        BigDecimal subtotal = nz(document.getSubtotal());
        BigDecimal totalAmount = nz(document.getTotalAmount());
        BigDecimal discountAmount = nz(document.getDiscountAmount());
        BigDecimal linesRawSum = documentLines.stream()
                .map(line -> nz(line.getLineTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (discountAmount.compareTo(BigDecimal.ZERO) == 0 && linesRawSum.compareTo(BigDecimal.ZERO) > 0) {
            subtotal = linesRawSum.setScale(2, RoundingMode.HALF_UP);
            totalAmount = subtotal;
        }
        BigDecimal discountRatio = resolveDiscountRatio(subtotal, totalAmount, linesRawSum);

        StringBuilder itemsXml = new StringBuilder();
        BigDecimal totalIva = BigDecimal.ZERO;
        BigDecimal granTotalFromLines = BigDecimal.ZERO;
        int lineNo = 0;
        for (TaxInvoiceDocument.Line line : documentLines) {
            lineNo++;
            BigDecimal lineTotal = nz(line.getLineTotal())
                    .multiply(discountRatio)
                    .setScale(2, RoundingMode.HALF_UP);
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(lineTotal);
            if (lineTotal.compareTo(BigDecimal.ZERO) <= 0
                    && nz(line.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            totalIva = totalIva.add(iva.tax());
            granTotalFromLines = granTotalFromLines.add(lineTotal);
            BigDecimal qty = nz(line.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unitPrice = qty.compareTo(BigDecimal.ZERO) > 0
                    ? lineTotal.divide(qty, 2, RoundingMode.HALF_UP)
                    : lineTotal;
            String desc = safe(line.getDescription());
            if (desc.isBlank()) {
                desc = "Producto";
            }
            itemsXml.append("""
                    <dte:Item BienOServicio="B" NumeroLinea="%d">
                      <dte:Cantidad>%s</dte:Cantidad>
                      <dte:UnidadMedida>UNI</dte:UnidadMedida>
                      <dte:Descripcion>%s</dte:Descripcion>
                      <dte:PrecioUnitario>%s</dte:PrecioUnitario>
                      <dte:Precio>%s</dte:Precio>
                      <dte:Descuento>0.00</dte:Descuento>
                      <dte:Impuestos>
                        <dte:Impuesto>
                          <dte:NombreCorto>IVA</dte:NombreCorto>
                          <dte:CodigoUnidadGravable>1</dte:CodigoUnidadGravable>
                          <dte:MontoGravable>%s</dte:MontoGravable>
                          <dte:MontoImpuesto>%s</dte:MontoImpuesto>
                        </dte:Impuesto>
                      </dte:Impuestos>
                      <dte:Total>%s</dte:Total>
                    </dte:Item>
                    """.formatted(
                    lineNo,
                    fmt(qty),
                    FelXmlEscaper.escape(desc),
                    fmt(unitPrice),
                    fmt(lineTotal),
                    fmt(iva.gravable()),
                    fmt(iva.tax()),
                    fmt(lineTotal)
            ));
        }

        String frasesXml = buildFrasesXml(credentials.frases());
        String adendaXml = buildAdendaXml(document.getInternalNumber());
        BigDecimal granTotal = granTotalFromLines.setScale(2, RoundingMode.HALF_UP);

        String establishmentCode = firstNonBlank(
                document.getEmitterEstablishmentCode(), properties.getCodigoEstablecimiento());
        String documentType = resolveEmissionDocumentType(document.getDocumentType(), establishmentCode);
        document.setDocumentType(documentType);
        String commercialName = firstNonBlank(document.getEmitterCommercialName(), credentials.nombreComercial());
        String addressLine = firstNonBlank(document.getEmitterAddressLine(), credentials.direccion());
        String municipio = firstNonBlank(document.getEmitterMunicipio(), credentials.municipio());
        String departamento = firstNonBlank(document.getEmitterDepartamento(), credentials.departamento());
        String receptorAddress = normalizeReceptorAddress(document.getAddress());
        // FCAM: abono único quemado (1 / total factura / fecha GT de facturación)
        String complementosXml = buildFcamAbonosComplemento(documentType, granTotal, emission);

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <dte:GTDocumento xmlns:dte="http://www.sat.gob.gt/dte/fel/0.2.0" xmlns:xd="http://www.w3.org/2000/09/xmldsig#" Version="0.1">
                  <dte:SAT ClaseDocumento="dte">
                    <dte:DTE ID="DatosCertificados">
                      <dte:DatosEmision ID="DatosEmision">
                        <dte:DatosGenerales CodigoMoneda="%s" FechaHoraEmision="%s" Tipo="%s"/>
                        <dte:Emisor AfiliacionIVA="%s" CodigoEstablecimiento="%s" CorreoEmisor="%s" NITEmisor="%s" NombreComercial="%s" NombreEmisor="%s">
                          <dte:DireccionEmisor>
                            <dte:Direccion>%s</dte:Direccion>
                            <dte:CodigoPostal>%s</dte:CodigoPostal>
                            <dte:Municipio>%s</dte:Municipio>
                            <dte:Departamento>%s</dte:Departamento>
                            <dte:Pais>%s</dte:Pais>
                          </dte:DireccionEmisor>
                        </dte:Emisor>
                        <dte:Receptor CorreoReceptor="%s" IDReceptor="%s" NombreReceptor="%s">
                          <dte:DireccionReceptor>
                            <dte:Direccion>%s</dte:Direccion>
                            <dte:CodigoPostal>0</dte:CodigoPostal>
                            <dte:Municipio/>
                            <dte:Departamento/>
                            <dte:Pais>GT</dte:Pais>
                          </dte:DireccionReceptor>
                        </dte:Receptor>
                        %s
                        <dte:Items>
                          %s
                        </dte:Items>
                        <dte:Totales>
                          <dte:TotalImpuestos>
                            <dte:TotalImpuesto NombreCorto="IVA" TotalMontoImpuesto="%s"/>
                          </dte:TotalImpuestos>
                          <dte:GranTotal>%s</dte:GranTotal>
                        </dte:Totales>
                        %s
                      </dte:DatosEmision>
                    </dte:DTE>
                    %s
                  </dte:SAT>
                </dte:GTDocumento>
                """.formatted(
                properties.getMoneda(),
                fechaHora,
                documentType,
                credentials.afiliacionIva(),
                FelXmlEscaper.escape(establishmentCode),
                FelXmlEscaper.escape(safe(credentials.correoEmisor())),
                FelXmlEscaper.escape(credentials.nitEmisor()),
                FelXmlEscaper.escape(commercialName),
                FelXmlEscaper.escape(safe(credentials.nombreEmisor())),
                FelXmlEscaper.escape(addressLine),
                FelXmlEscaper.escape(safe(properties.getCodigoPostal())),
                FelXmlEscaper.escape(municipio),
                FelXmlEscaper.escape(departamento),
                FelXmlEscaper.escape(safe(properties.getPais())),
                FelXmlEscaper.escape(receptorEmail),
                FelXmlEscaper.escape(receptorId),
                FelXmlEscaper.escape(receptorName),
                FelXmlEscaper.escape(receptorAddress),
                frasesXml,
                itemsXml,
                fmt(totalIva.setScale(2, RoundingMode.HALF_UP)),
                fmt(granTotal),
                complementosXml,
                adendaXml
        );
    }

    /**
     * Establecimiento FEL "1" (CUEROGLAM central) emite Factura Cambiaria (FCAM).
     * El resto de establecimientos emiten Factura (FACT).
     */
    static boolean isFcamEstablishment(String establishmentCode) {
        return "1".equals(safe(establishmentCode));
    }

    static String resolveEmissionDocumentType(String requestedType, String establishmentCode) {
        if (isFcamEstablishment(establishmentCode)) {
            return "FCAM";
        }
        String requested = safe(requestedType).toUpperCase(Locale.ROOT);
        if ("FCAM".equals(requested) || "FACT".equals(requested)) {
            return requested;
        }
        return "FACT";
    }

    /**
     * Complemento requerido por SAT para FCAM.
     * Valores fijos de negocio:
     * - NumeroAbono = 1
     * - MontoAbono = total de la factura (GranTotal)
     * - FechaVencimiento = fecha de facturación en zona Guatemala (yyyy-MM-dd)
     */
    private String buildFcamAbonosComplemento(
            String documentType, BigDecimal invoiceTotal, ZonedDateTime emissionGuatemala) {
        if (!"FCAM".equalsIgnoreCase(safe(documentType))) {
            return "";
        }
        BigDecimal montoAbono = nz(invoiceTotal).setScale(2, RoundingMode.HALF_UP);
        if (montoAbono.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        ZonedDateTime emission = emissionGuatemala != null
                ? emissionGuatemala.withZoneSameInstant(GUATEMALA)
                : ZonedDateTime.now(GUATEMALA);
        String fechaVencimiento = emission.toLocalDate().toString();
        // Atributos alineados a ejemplos FEL (INFILE/Megaprint): IDComplemento=1, NombreComplemento=Abono
        return """
                <dte:Complementos>
                  <dte:Complemento IDComplemento="1" NombreComplemento="Abono" URIComplemento="http://www.sat.gob.gt/dte/fel/CompCambiaria/0.1.0">
                    <cfc:AbonosFacturaCambiaria xmlns:cfc="http://www.sat.gob.gt/dte/fel/CompCambiaria/0.1.0" Version="1">
                      <cfc:Abono>
                        <cfc:NumeroAbono>1</cfc:NumeroAbono>
                        <cfc:FechaVencimiento>%s</cfc:FechaVencimiento>
                        <cfc:MontoAbono>%s</cfc:MontoAbono>
                      </cfc:Abono>
                    </cfc:AbonosFacturaCambiaria>
                  </dte:Complemento>
                </dte:Complementos>
                """.formatted(fechaVencimiento, fmt(montoAbono));
    }

    /**
     * Adenda: información no tributaria dentro de {@code SAT}, hermana de {@code DTE}
     * (esquema GT_Documento-0.2.0). No va como hijo directo de {@code GTDocumento}.
     */
    private String buildAdendaXml(String internalNumber) {
        if (internalNumber == null || internalNumber.isBlank()) {
            return "";
        }
        String trimmed = internalNumber.trim();
        if (looksLikePosSaleNumber(trimmed)) {
            return "";
        }
        String value = FelXmlEscaper.escape(trimmed);
        // INFILE imprime "Control:" en la representación gráfica leyendo la adenda.
        // Enviamos ambas etiquetas por compatibilidad con plantillas distintas.
        return "<dte:Adenda><Control>"
                + value
                + "</Control><NumeroControlInterno>"
                + value
                + "</NumeroControlInterno></dte:Adenda>";
    }

    private String buildFrasesXml(List<FelEmissionProperties.Frase> frases) {
        if (frases == null || frases.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<dte:Frases>");
        for (FelEmissionProperties.Frase frase : frases) {
            sb.append("<dte:Frase CodigoEscenario=\"")
                    .append(frase.getEscenario())
                    .append("\" TipoFrase=\"")
                    .append(frase.getTipo())
                    .append("\"/>");
        }
        sb.append("</dte:Frases>");
        return sb.toString();
    }

    private static String normalizeReceptorId(String taxId) {
        String raw = safe(taxId).toUpperCase(Locale.ROOT).trim();
        if (raw.isBlank() || "CF".equals(raw) || "C/F".equals(raw)) {
            return "CF";
        }
        return raw.replace(" ", "").replace("-", "");
    }

    private static String normalizeReceptorName(String receptorId, String customerName) {
        if ("CF".equals(receptorId)) {
            return "CONSUMIDOR FINAL";
        }
        String name = safe(customerName).trim();
        return name.isBlank() ? "CONSUMIDOR FINAL" : name;
    }

    /** SAT acepta "Ciudad" para CF sin dirección; si el usuario capturó una, va al XML. */
    static String normalizeReceptorAddress(String address) {
        String value = safe(address);
        return value.isBlank() ? "Ciudad" : value;
    }

    /** Varios correos separados por ';' sin espacios (requisito FEL / INFILE). */
    static String normalizeReceptorEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Arrays.stream(raw.split("[;,]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(";"));
    }

    private static String fmt(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    /**
     * Aplica el descuento una sola vez: si las líneas ya suman el total cobrado (mapper POS antiguo),
     * no vuelve a prorratear.
     */
    static BigDecimal resolveDiscountRatio(BigDecimal subtotal, BigDecimal totalAmount, BigDecimal linesRawSum) {
        BigDecimal sub = nz(subtotal);
        BigDecimal total = nz(totalAmount);
        BigDecimal lines = nz(linesRawSum);
        if (sub.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        if (lines.subtract(total).abs().compareTo(new BigDecimal("0.05")) <= 0
                && total.compareTo(sub) < 0) {
            return BigDecimal.ONE;
        }
        return total.divide(sub, 8, RoundingMode.HALF_UP);
    }

    /** No usar número de venta POS como control interno en adenda FEL. */
    static boolean looksLikePosSaleNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.trim().toUpperCase(Locale.ROOT).matches("^POS-\\d{8}-\\d+$");
    }
}
