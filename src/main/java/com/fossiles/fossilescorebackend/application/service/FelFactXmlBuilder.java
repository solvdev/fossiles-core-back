package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
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
import java.util.ArrayList;
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
        List<BigDecimal> felLineTotals = resolveFelLineTotals(
                documentLines, subtotal, totalAmount, discountAmount);

        StringBuilder itemsXml = new StringBuilder();
        BigDecimal totalIva = BigDecimal.ZERO;
        BigDecimal granTotalFromLines = BigDecimal.ZERO;
        int lineNo = 0;
        for (int lineIdx = 0; lineIdx < documentLines.size(); lineIdx++) {
            TaxInvoiceDocument.Line line = documentLines.get(lineIdx);
            BigDecimal lineTotal = felLineTotals.get(lineIdx);
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(lineTotal);
            if (lineTotal.compareTo(BigDecimal.ZERO) <= 0
                    && nz(line.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            lineNo++;
            totalIva = totalIva.add(iva.tax());
            granTotalFromLines = granTotalFromLines.add(lineTotal);
            BigDecimal qty = nz(line.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unitPrice = qty.compareTo(BigDecimal.ZERO) > 0
                    ? lineTotal.divide(qty, 2, RoundingMode.HALF_UP)
                    : lineTotal;
            String desc = resolveFelItemDescripcionForDte(line);
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
        boolean hasInternal = internalNumber != null
                && !internalNumber.isBlank()
                && !looksLikePosSaleNumber(internalNumber.trim());
        if (!hasInternal) {
            return "";
        }
        String value = FelXmlEscaper.escape(internalNumber.trim());
        return "<dte:Adenda>"
                + "<Control>" + value + "</Control>"
                + "<NumeroControlInterno>" + value + "</NumeroControlInterno>"
                + "</dte:Adenda>";
    }

    /**
     * Valor de {@code dte:Descripcion} para plantilla INFILE/CUEROGLAM:
     * {@code Código | Descripción}. INFILE parte la columna COD antes del pipe.
     */
    static String resolveFelItemDescripcionForDte(TaxInvoiceDocument.Line line) {
        if (line == null) {
            return "Producto";
        }
        String productCode = safe(line.getProductCode()).trim();
        String description = resolveFelItemDescription(line);
        if (productCode.isBlank()) {
            return description.isBlank() ? "Producto" : description;
        }
        if (description.isBlank()) {
            return productCode;
        }
        return productCode + " | " + description;
    }

    /** Texto descriptivo del ítem sin el código de producto (parte posterior al pipe en INFILE). */
    static String resolveFelItemDescription(TaxInvoiceDocument.Line line) {
        if (line == null) {
            return "Producto";
        }
        String description = safe(line.getDescription());
        String productCode = safe(line.getProductCode()).trim();
        if (description.isBlank()) {
            return productCode.isBlank() ? "Producto" : productCode;
        }
        String pipeSplit = splitFelPipeDescription(description);
        if (pipeSplit != null) {
            return pipeSplit;
        }
        if (!productCode.isBlank() && startsWithToken(description, productCode)) {
            String withoutCode = stripLeadingToken(description, productCode).trim();
            if (!withoutCode.isBlank()) {
                return withoutCode;
            }
        }
        return description;
    }

    /** Código interno del producto para columna COD en plantilla INFILE (parte anterior al pipe). */
    static String resolveFelItemProductCode(TaxInvoiceDocument.Line line) {
        if (line == null) {
            return "";
        }
        return safe(line.getProductCode()).trim();
    }

    private static String splitFelPipeDescription(String description) {
        int pipeIdx = description.indexOf('|');
        if (pipeIdx < 0) {
            return null;
        }
        String afterPipe = description.substring(pipeIdx + 1).trim();
        return afterPipe.isBlank() ? null : afterPipe;
    }

    private static boolean startsWithToken(String text, String token) {
        if (text.length() < token.length()) {
            return false;
        }
        if (!text.regionMatches(true, 0, token, 0, token.length())) {
            return false;
        }
        return text.length() == token.length()
                || Character.isWhitespace(text.charAt(token.length()))
                || text.charAt(token.length()) == '-';
    }

    private static String stripLeadingToken(String text, String token) {
        String rest = text.substring(token.length()).trim();
        if (rest.startsWith("-")) {
            rest = rest.substring(1).trim();
        }
        return rest;
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
     * Empaques SUM- nunca llevan descuento en POS; la factura debe reflejar lo mismo.
     * El descuento global se reparte solo entre líneas elegibles (no empaque).
     */
    static boolean isPackagingLine(TaxInvoiceDocument.Line line) {
        return line != null && ProductCinchoType.isPackagingProductCode(line.getProductCode());
    }

    /**
     * Totales por línea para el XML FEL. Empaques a precio de lista; productos con descuento POS.
     */
    static List<BigDecimal> resolveFelLineTotals(
            List<TaxInvoiceDocument.Line> lines,
            BigDecimal subtotal,
            BigDecimal totalAmount,
            BigDecimal discountAmount
    ) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> rawTotals = lines.stream()
                .map(line -> nz(line.getLineTotal()))
                .toList();
        BigDecimal linesRawSum = rawTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = nz(discountAmount);
        BigDecimal total = nz(totalAmount);
        BigDecimal sub = nz(subtotal);

        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            return new ArrayList<>(rawTotals);
        }

        // Líneas ya traen precio neto (mapper antiguo): no volver a descontar.
        if (linesRawSum.subtract(total).abs().compareTo(new BigDecimal("0.05")) <= 0
                && total.compareTo(sub) < 0) {
            return new ArrayList<>(rawTotals);
        }

        List<Integer> eligibleIndexes = new ArrayList<>();
        BigDecimal eligibleSum = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            if (!isPackagingLine(lines.get(i))) {
                eligibleIndexes.add(i);
                eligibleSum = eligibleSum.add(rawTotals.get(i));
            }
        }

        if (eligibleIndexes.isEmpty()) {
            return new ArrayList<>(rawTotals);
        }

        BigDecimal discountApplied = discount.min(eligibleSum).setScale(2, RoundingMode.HALF_UP);
        BigDecimal eligibleNet = eligibleSum.subtract(discountApplied).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        List<BigDecimal> result = new ArrayList<>(rawTotals);
        if (eligibleSum.compareTo(BigDecimal.ZERO) <= 0) {
            return result;
        }

        BigDecimal allocated = BigDecimal.ZERO;
        for (int j = 0; j < eligibleIndexes.size(); j++) {
            int idx = eligibleIndexes.get(j);
            BigDecimal raw = rawTotals.get(idx);
            BigDecimal net;
            if (j == eligibleIndexes.size() - 1) {
                net = eligibleNet.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else {
                net = eligibleNet.multiply(raw)
                        .divide(eligibleSum, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(net);
            }
            result.set(idx, net);
        }
        return result;
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
