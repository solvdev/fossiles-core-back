package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
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
import java.util.Locale;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FelFactXmlBuilder {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter FEL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final FelEmissionProperties properties;

    public String buildUnsignedXml(TaxInvoiceDocument document) {
        FelEmissionProperties cfg = properties;
        ZonedDateTime emission = document.getIssuedAt() != null
                ? document.getIssuedAt().atZone(GUATEMALA)
                : ZonedDateTime.now(GUATEMALA);
        String fechaHora = emission.format(FEL_DATE_TIME);

        String receptorId = normalizeReceptorId(document.getCustomerTaxId());
        String receptorName = normalizeReceptorName(receptorId, document.getCustomerName());

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
        BigDecimal discountRatio = subtotal.compareTo(BigDecimal.ZERO) > 0
                ? totalAmount.divide(subtotal, 8, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        StringBuilder itemsXml = new StringBuilder();
        BigDecimal totalIva = BigDecimal.ZERO;
        BigDecimal granTotalFromLines = BigDecimal.ZERO;
        int lineNo = 0;
        for (TaxInvoiceDocument.Line line : documentLines) {
            lineNo++;
            BigDecimal lineTotal = nz(line.getLineTotal())
                    .multiply(discountRatio)
                    .setScale(2, RoundingMode.HALF_UP);
            if (lineTotal.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(lineTotal);
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

        String frasesXml = buildFrasesXml(cfg);
        BigDecimal granTotal = granTotalFromLines.setScale(2, RoundingMode.HALF_UP);

        String establishmentCode = firstNonBlank(document.getEmitterEstablishmentCode(), cfg.getCodigoEstablecimiento());
        String addressLine = firstNonBlank(document.getEmitterAddressLine(), cfg.getDireccion());
        String municipio = firstNonBlank(document.getEmitterMunicipio(), cfg.getMunicipio());
        String departamento = firstNonBlank(document.getEmitterDepartamento(), cfg.getDepartamento());

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
                        <dte:Receptor CorreoReceptor="" IDReceptor="%s" NombreReceptor="%s">
                          <dte:DireccionReceptor>
                            <dte:Direccion>Ciudad</dte:Direccion>
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
                      </dte:DatosEmision>
                    </dte:DTE>
                  </dte:SAT>
                </dte:GTDocumento>
                """.formatted(
                cfg.getMoneda(),
                fechaHora,
                cfg.getDocumentType(),
                cfg.getAfiliacionIva(),
                FelXmlEscaper.escape(establishmentCode),
                FelXmlEscaper.escape(safe(cfg.getCorreoEmisor())),
                FelXmlEscaper.escape(cfg.getNitEmisor()),
                FelXmlEscaper.escape(safe(cfg.getNombreComercial())),
                FelXmlEscaper.escape(safe(cfg.getNombreEmisor())),
                FelXmlEscaper.escape(addressLine),
                FelXmlEscaper.escape(safe(cfg.getCodigoPostal())),
                FelXmlEscaper.escape(municipio),
                FelXmlEscaper.escape(departamento),
                FelXmlEscaper.escape(safe(cfg.getPais())),
                FelXmlEscaper.escape(receptorId),
                FelXmlEscaper.escape(receptorName),
                frasesXml,
                itemsXml,
                fmt(totalIva.setScale(2, RoundingMode.HALF_UP)),
                fmt(granTotal)
        );
    }

    private String buildFrasesXml(FelEmissionProperties cfg) {
        if (cfg.getFrases() == null || cfg.getFrases().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<dte:Frases>");
        for (FelEmissionProperties.Frase frase : cfg.getFrases()) {
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
}
