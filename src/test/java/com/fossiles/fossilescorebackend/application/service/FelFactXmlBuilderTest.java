package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.FelIvaCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FelFactXmlBuilderTest {

    @Test
    void buildsFactXmlWithReceptorAndItems() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setNombreComercial("FOSSILES");
        props.setDireccion("Zona 10 Guatemala");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .transactionId("POS-20260101-001")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .documentType("FACT")
                .emitterEstablishmentCode("2")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("100.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("BOL-01 Bolso Negro")
                                .quantity(new BigDecimal("1"))
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("Tipo=\"FACT\"");
        assertThat(xml).doesNotContain("AbonosFacturaCambiaria");
        assertThat(xml).contains("IDReceptor=\"CF\"");
        assertThat(xml).contains("NombreReceptor=\"CONSUMIDOR FINAL\"");
        assertThat(xml).contains("CorreoReceptor=\"\"");
        assertThat(xml).contains("Bolso");
        assertThat(xml).contains("<dte:GranTotal>100.00</dte:GranTotal>");
    }

    @Test
    void usesLocationEstablishmentCodeOverride() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setCodigoEstablecimiento("1");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("46")
                .transactionId("POS-20260101-002")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto prueba")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("CodigoEstablecimiento=\"46\"");
        assertThat(xml).doesNotContain("CodigoEstablecimiento=\"1\"");
    }

    @Test
    void usesLocationCommercialNameOverride() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setNombreComercial("CUEROGLAM");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("9");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("9")
                .emitterCommercialName("CUEROGLAM MIRAFLORES II")
                .emitterAddressLine("21 AVENIDA 4-32 CENTRO COMERCIAL MIRAFLORES 2DO NIVEL FASE 3 KIOSKO K-9 ZONA 11")
                .emitterMunicipio("Guatemala")
                .emitterDepartamento("GUATEMALA")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto prueba")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("NombreComercial=\"CUEROGLAM MIRAFLORES II\"");
        assertThat(xml).doesNotContain("NombreComercial=\"CUEROGLAM\"");
        assertThat(xml).contains("NombreEmisor=\"CUEROGLAM, SOCIEDAD ANONIMA\"");
    }

    @Test
    void usesReceptorAddressWhenProvided() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("1234567-8")
                .customerName("CLIENTE PRUEBA")
                .address("5a Avenida 12-34 Zona 10, Guatemala")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<dte:Direccion>5a Avenida 12-34 Zona 10, Guatemala</dte:Direccion>");
    }

    @Test
    void fallsBackToCiudadWhenReceptorAddressMissing() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<dte:DireccionReceptor>");
        assertThat(xml).contains("<dte:Direccion>Ciudad</dte:Direccion>");
    }

    @Test
    void includesReceptorEmailSemicolonSeparated() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("1234567-8")
                .customerName("CLIENTE PRUEBA")
                .email("uno@mail.com; dos@mail.com")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("CorreoReceptor=\"uno@mail.com;dos@mail.com\"");
    }

    @Test
    void appliesPosDiscountOnceToInvoiceTotal() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setNombreComercial("CUEROGLAM");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 6, 17, 10, 30))
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("346.00"))
                .discountAmount(new BigDecimal("51.90"))
                .totalAmount(new BigDecimal("294.10"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Bolso")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("346.00"))
                                .lineTotal(new BigDecimal("346.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<dte:GranTotal>294.10</dte:GranTotal>");
        assertThat(xml).doesNotContain("<dte:GranTotal>249.99</dte:GranTotal>");
    }

    @Test
    void exemptsPackagingFromGlobalDiscountOnInvoiceLines() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 7, 21, 10, 30))
                .customerTaxId("1507435")
                .customerName("FREDY ROLANDO HERNANDEZ OLA")
                .subtotal(new BigDecimal("537.00"))
                .discountAmount(new BigDecimal("80.25"))
                .totalAmount(new BigDecimal("456.75"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .productCode("B-46")
                                .description("BILLETERA JOHANA TOSTADO")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("535.00"))
                                .lineTotal(new BigDecimal("535.00"))
                                .build(),
                        TaxInvoiceDocument.Line.builder()
                                .productCode("SUM-CAJA-P")
                                .description("CAJA PORTACHEQUERA FOSS")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(BigDecimal.ZERO)
                                .lineTotal(BigDecimal.ZERO)
                                .build(),
                        TaxInvoiceDocument.Line.builder()
                                .productCode("SUM-BL-M")
                                .description("BOLSA EMPAQUE MEDIANA")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("2.00"))
                                .lineTotal(new BigDecimal("2.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<dte:Total>454.75</dte:Total>");
        assertThat(xml).contains("<dte:Total>2.00</dte:Total>");
        assertThat(xml).doesNotContain("<dte:Total>455.05</dte:Total>");
        assertThat(xml).doesNotContain("<dte:Total>1.70</dte:Total>");
        assertThat(xml).contains("<dte:GranTotal>456.75</dte:GranTotal>");
    }

    @Test
    void resolveFelLineTotals_keepsPackagingAtListPrice() {
        List<TaxInvoiceDocument.Line> lines = List.of(
                TaxInvoiceDocument.Line.builder()
                        .productCode("B-46")
                        .lineTotal(new BigDecimal("535.00"))
                        .build(),
                TaxInvoiceDocument.Line.builder()
                        .productCode("SUM-BL-M")
                        .lineTotal(new BigDecimal("2.00"))
                        .build()
        );

        List<BigDecimal> totals = FelFactXmlBuilder.resolveFelLineTotals(
                lines,
                new BigDecimal("537.00"),
                new BigDecimal("456.75"),
                new BigDecimal("80.25"));

        assertThat(totals).containsExactly(
                new BigDecimal("454.75"),
                new BigDecimal("2.00")
        );
    }

    @Test
    void includesInternalControlNumberInAdenda() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .internalNumber("A45-241")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<Control>A45-241</Control>");
        assertThat(xml).contains("<NumeroControlInterno>A45-241</NumeroControlInterno>");
        int dteClose = xml.indexOf("</dte:DTE>");
        int adendaOpen = xml.indexOf("<dte:Adenda>");
        int satClose = xml.indexOf("</dte:SAT>");
        assertThat(adendaOpen).isGreaterThan(dteClose);
        assertThat(adendaOpen).isLessThan(satClose);
    }

    @Test
    void formatsProductCodeAndDescriptionInFelDescripcion() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .internalNumber("A15-99")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("441.45"))
                .totalAmount(new BigDecimal("441.45"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .productCode("B-23-1")
                                .description("BILLETERA MEGAN CON HOJAS NEGRO")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("439.75"))
                                .lineTotal(new BigDecimal("439.75"))
                                .build(),
                        TaxInvoiceDocument.Line.builder()
                                .productCode("SUM-BL-M")
                                .description("BOLSA EMPAQUE MEDIANA")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("1.70"))
                                .lineTotal(new BigDecimal("1.70"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).doesNotContain("<Codigos>");
        assertThat(xml).doesNotContain("<dte:CodigoProducto>");
        assertThat(xml).contains("<dte:Descripcion>B-23-1 | BILLETERA MEGAN CON HOJAS NEGRO</dte:Descripcion>");
        assertThat(xml).contains("<dte:Descripcion>SUM-BL-M | BOLSA EMPAQUE MEDIANA</dte:Descripcion>");
    }

    @Test
    void stripsLegacyCombinedDescriptionWhenProductCodePresent() {
        TaxInvoiceDocument.Line line = TaxInvoiceDocument.Line.builder()
                .productCode("B-23-1")
                .description("B-23-1 BILLETERA MEGAN CON HOJAS NEGRO")
                .build();

        assertThat(FelFactXmlBuilder.resolveFelItemDescription(line))
                .isEqualTo("BILLETERA MEGAN CON HOJAS NEGRO");
        assertThat(FelFactXmlBuilder.resolveFelItemDescripcionForDte(line))
                .isEqualTo("B-23-1 | BILLETERA MEGAN CON HOJAS NEGRO");
        assertThat(FelFactXmlBuilder.resolveFelItemProductCode(line)).isEqualTo("B-23-1");
    }

    @Test
    void excludesPosSaleNumberFromAdenda() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .internalNumber("POS-20260721-0042")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("50.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("50.00"))
                                .lineTotal(new BigDecimal("50.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).doesNotContain("<dte:Adenda>");
        assertThat(xml).doesNotContain("POS-20260721-0042");
    }

    @Test
    void includesZeroValueLineInItems() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("2")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .internalNumber("A15-6107")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("100.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Bolso")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .build(),
                        TaxInvoiceDocument.Line.builder()
                                .description("Empaque SUM")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("5.00"))
                                .lineTotal(BigDecimal.ZERO)
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("Empaque SUM");
        assertThat(xml).contains("<Control>A15-6107</Control>");
        assertThat(xml).contains("<dte:GranTotal>100.00</dte:GranTotal>");
    }

    @Test
    void establishmentOneEmitsFcamWithAbonosComplemento() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setNombreComercial("CUEROGLAM");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("1");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .emitterEstablishmentCode("1")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("100.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .description("Producto central")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("Tipo=\"FCAM\"");
        assertThat(xml).contains("CodigoEstablecimiento=\"1\"");
        assertThat(xml).contains("NombreComplemento=\"Abono\"");
        assertThat(xml).contains("IDComplemento=\"1\"");
        assertThat(xml).contains("URIComplemento=\"http://www.sat.gob.gt/dte/fel/CompCambiaria/0.1.0\"");
        assertThat(xml).contains("AbonosFacturaCambiaria");
        assertThat(xml).contains("<cfc:NumeroAbono>1</cfc:NumeroAbono>");
        assertThat(xml).contains("<cfc:FechaVencimiento>2026-01-15</cfc:FechaVencimiento>");
        assertThat(xml).contains("<cfc:MontoAbono>100.00</cfc:MontoAbono>");
        assertThat(xml).contains("<dte:GranTotal>100.00</dte:GranTotal>");
    }

    @Test
    void bundleLineStaysBalancedWhenUnitPriceDoesNotDivide() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setNombreComercial("FOSSILES");
        props.setDireccion("Zona 10 Guatemala");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento("2");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .emitterEstablishmentCode("2")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("230.00"))
                .totalAmount(new BigDecimal("230.00"))
                .lines(List.of(
                        TaxInvoiceDocument.Line.builder()
                                .productCode("B-1")
                                .description("Billetera B-1")
                                .quantity(new BigDecimal("3"))
                                .unitPrice(new BigDecimal("43.33"))
                                .lineTotal(new BigDecimal("130.00"))
                                .bundlePriced(true)
                                .build(),
                        TaxInvoiceDocument.Line.builder()
                                .description("Cincho")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .build()
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains("<dte:PrecioUnitario>43.34</dte:PrecioUnitario>");
        assertThat(xml).contains("<dte:Precio>130.02</dte:Precio>");
        assertThat(xml).contains("<dte:Descuento>0.02</dte:Descuento>");
        assertThat(xml).contains("<dte:Total>130.00</dte:Total>");
        assertThat(xml).contains("<dte:PrecioUnitario>100.00</dte:PrecioUnitario>");
        assertThat(xml).contains("<dte:Precio>100.00</dte:Precio>");
        assertThat(xml).contains("<dte:Descuento>0.00</dte:Descuento>");
        assertThat(xml).contains("<dte:GranTotal>230.00</dte:GranTotal>");

        FelFactXmlBuilder.FelLineMoney six = FelFactXmlBuilder.resolveFelLineMoney(
                new BigDecimal("6"), new BigDecimal("260.00"), true);
        assertThat(six.unitPrice()).isEqualByComparingTo("43.34");
        assertThat(six.gross()).isEqualByComparingTo("260.04");
        assertThat(six.discount()).isEqualByComparingTo("0.04");
        assertThat(six.unitPrice().multiply(new BigDecimal("6")).setScale(2, RoundingMode.HALF_UP)
                .subtract(six.discount())).isEqualByComparingTo("260.00");
    }

    @Test
    void regularLinesMatchMainXml() {
        FelEmissionProperties props = emitter("2");
        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .emitterEstablishmentCode("2")
                .documentType("FACT")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal("180.00"))
                .totalAmount(new BigDecimal("180.00"))
                .lines(List.of(
                        line("Cincho descuento", "3", "100.00", false),
                        line("Cincho par", "2", "80.00", false)
                ))
                .build();

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        assertThat(xml).contains(mainItemXml(1, "3.00", "Cincho descuento", "100.00"));
        assertThat(xml).contains(mainItemXml(2, "2.00", "Cincho par", "80.00"));
        assertThat(xml).doesNotContain("33.34");
        assertThat(xml).doesNotContain("100.02");
        assertThat(xml).doesNotContain("<dte:Descuento>0.02</dte:Descuento>");
    }

    @Test
    void b1BundleThreeAndFourUnits() {
        FelFactXmlBuilder.FelLineMoney three = FelFactXmlBuilder.resolveFelLineMoney(
                new BigDecimal("3"), new BigDecimal("130.00"), true);
        assertThat(three.unitPrice()).isEqualByComparingTo("43.34");
        assertThat(three.gross()).isEqualByComparingTo("130.02");
        assertThat(three.discount()).isEqualByComparingTo("0.02");
        assertThat(three.total()).isEqualByComparingTo("130.00");

        FelFactXmlBuilder.FelLineMoney four = FelFactXmlBuilder.resolveFelLineMoney(
                new BigDecimal("4"), new BigDecimal("170.00"), true);
        assertThat(four.unitPrice()).isEqualByComparingTo("42.50");
        assertThat(four.gross()).isEqualByComparingTo("170.00");
        assertThat(four.discount()).isEqualByComparingTo("0.00");
        assertThat(four.total()).isEqualByComparingTo("170.00");

        FelEmissionProperties props = emitter("2");
        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(
                invoice("2", "FACT", List.of(
                        bundleLine("3", "43.33", "130.00"),
                        bundleLine("4", "42.50", "170.00")
                ), "300.00"),
                props.resolveCredentials(false));

        assertThat(xml).contains("<dte:PrecioUnitario>43.34</dte:PrecioUnitario>");
        assertThat(xml).contains("<dte:Precio>130.02</dte:Precio>");
        assertThat(xml).contains("<dte:Descuento>0.02</dte:Descuento>");
        assertThat(xml).contains("<dte:Total>130.00</dte:Total>");
        assertThat(xml).contains("<dte:PrecioUnitario>42.50</dte:PrecioUnitario>");
        assertThat(xml).contains("<dte:Precio>170.00</dte:Precio>");
        assertThat(xml).contains("<dte:Descuento>0.00</dte:Descuento>");
        assertThat(xml).contains("<dte:Total>170.00</dte:Total>");
    }

    @Test
    void bundleAdjustmentSkipsZeroFractionalNegativeAndCreditNotes() {
        FelFactXmlBuilder.FelLineMoney zeroQty = FelFactXmlBuilder.resolveFelLineMoney(
                BigDecimal.ZERO, new BigDecimal("130.00"), true);
        assertThat(zeroQty.unitPrice()).isEqualByComparingTo("130.00");
        assertThat(zeroQty.gross()).isEqualByComparingTo("130.00");
        assertThat(zeroQty.discount()).isEqualByComparingTo("0.00");
        assertThat(zeroQty.total()).isEqualByComparingTo("130.00");

        FelFactXmlBuilder.FelLineMoney fraction = FelFactXmlBuilder.resolveFelLineMoney(
                new BigDecimal("3.5"), new BigDecimal("130.00"), true);
        assertThat(fraction.unitPrice()).isEqualByComparingTo("37.14");
        assertThat(fraction.gross()).isEqualByComparingTo("130.00");
        assertThat(fraction.discount()).isEqualByComparingTo("0.00");
        assertThat(fraction.total()).isEqualByComparingTo("130.00");

        FelFactXmlBuilder.FelLineMoney negative = FelFactXmlBuilder.resolveFelLineMoney(
                new BigDecimal("3"), new BigDecimal("-130.00"), true);
        assertThat(negative.unitPrice()).isEqualByComparingTo("-43.33");
        assertThat(negative.gross()).isEqualByComparingTo("-130.00");
        assertThat(negative.discount()).isEqualByComparingTo("0.00");
        assertThat(negative.total()).isEqualByComparingTo("-130.00");

        FelEmissionProperties props = emitter("2");
        FelFactXmlBuilder builder = new FelFactXmlBuilder(props);
        for (String noteType : List.of("NCRE", "NDEB")) {
            String xml = builder.buildUnsignedXml(
                    invoice("2", noteType, List.of(bundleLine("3", "43.33", "130.00")), "130.00"),
                    props.resolveCredentials(false));
            assertThat(xml).contains(mainItemXml(1, "3.00", "B-1 | Billetera B-1", "130.00"));
            assertThat(xml).doesNotContain("43.34");
            assertThat(xml).doesNotContain("130.02");
        }

        String negativeXml = builder.buildUnsignedXml(
                invoice("2", "FACT", List.of(
                        TaxInvoiceDocument.Line.builder()
                                .productCode("B-1")
                                .description("Billetera B-1")
                                .quantity(new BigDecimal("3"))
                                .unitPrice(new BigDecimal("-43.33"))
                                .lineTotal(new BigDecimal("-130.00"))
                                .bundlePriced(true)
                                .build()
                ), "-130.00"),
                props.resolveCredentials(false));
        assertThat(negativeXml).contains("<dte:PrecioUnitario>-43.33</dte:PrecioUnitario>");
        assertThat(negativeXml).contains("<dte:Precio>-130.00</dte:Precio>");
        assertThat(negativeXml).contains("<dte:Descuento>0.00</dte:Descuento>");
        assertThat(negativeXml).doesNotContain("-129.99");
        assertThat(negativeXml).doesNotContain("<dte:Descuento>0.01</dte:Descuento>");
    }

    @Test
    void invoiceLineTotalsMatchGranTotalAndIva() {
        FelEmissionProperties props = emitter("2");
        TaxInvoiceDocument document = invoice("2", "FACT", List.of(
                bundleLine("3", "43.33", "130.00"),
                line("Cincho descuento", "3", "100.00", false),
                line("Cincho", "1", "50.00", false)
        ), "280.00");

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));

        List<BigDecimal> lineTotals = xmlAmounts(xml, "<dte:Total>");
        assertThat(lineTotals).containsExactly(
                new BigDecimal("130.00"), new BigDecimal("100.00"), new BigDecimal("50.00"));
        BigDecimal summed = lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo("280.00");
        assertThat(xmlAmounts(xml, "<dte:GranTotal>")).containsExactly(summed);

        List<BigDecimal> gravable = xmlAmounts(xml, "<dte:MontoGravable>");
        List<BigDecimal> tax = xmlAmounts(xml, "<dte:MontoImpuesto>");
        assertThat(gravable).hasSize(lineTotals.size());
        BigDecimal taxSum = BigDecimal.ZERO;
        for (int i = 0; i < lineTotals.size(); i++) {
            assertThat(gravable.get(i).add(tax.get(i))).isEqualByComparingTo(lineTotals.get(i));
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(lineTotals.get(i));
            assertThat(gravable.get(i)).isEqualByComparingTo(iva.gravable());
            assertThat(tax.get(i)).isEqualByComparingTo(iva.tax());
            taxSum = taxSum.add(tax.get(i));
        }
        assertThat(xmlAmounts(xml, "TotalMontoImpuesto=\"").get(0)).isEqualByComparingTo(taxSum);
        assertThat(xml).contains("<dte:PrecioUnitario>43.34</dte:PrecioUnitario>");
        assertThat(xml).contains(mainItemXml(2, "3.00", "Cincho descuento", "100.00"));
    }

    private static FelEmissionProperties emitter(String establishment) {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setNombreComercial("FOSSILES");
        props.setDireccion("Zona 10 Guatemala");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento(establishment);
        return props;
    }

    private static TaxInvoiceDocument invoice(
            String establishment,
            String documentType,
            List<TaxInvoiceDocument.Line> lines,
            String total
    ) {
        return TaxInvoiceDocument.builder()
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                .emitterEstablishmentCode(establishment)
                .documentType(documentType)
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .subtotal(new BigDecimal(total))
                .totalAmount(new BigDecimal(total))
                .lines(lines)
                .build();
    }

    private static TaxInvoiceDocument.Line line(
            String description, String quantity, String total, boolean bundlePriced) {
        return TaxInvoiceDocument.Line.builder()
                .description(description)
                .quantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal(total))
                .lineTotal(new BigDecimal(total))
                .bundlePriced(bundlePriced)
                .build();
    }

    private static TaxInvoiceDocument.Line bundleLine(String quantity, String unitPrice, String total) {
        return TaxInvoiceDocument.Line.builder()
                .productCode("B-1")
                .description("Billetera B-1")
                .quantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal(unitPrice))
                .lineTotal(new BigDecimal(total))
                .bundlePriced(true)
                .build();
    }

    /** Ítem tal como lo escribía main: unitario HALF_UP, precio = total, descuento 0.00. */
    private static String mainItemXml(int lineNo, String quantity, String description, String lineTotal) {
        BigDecimal total = new BigDecimal(lineTotal);
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal unit = qty.compareTo(BigDecimal.ZERO) > 0
                ? total.divide(qty, 2, RoundingMode.HALF_UP)
                : total;
        FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(total);
        return """
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
                quantity,
                description,
                unit.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                total.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                iva.gravable().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                iva.tax().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                total.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private static List<BigDecimal> xmlAmounts(String xml, String openTag) {
        Pattern pattern = Pattern.compile(Pattern.quote(openTag) + "(-?[0-9]+\\.[0-9]{2})");
        Matcher matcher = pattern.matcher(xml);
        List<BigDecimal> amounts = new ArrayList<>();
        while (matcher.find()) {
            amounts.add(new BigDecimal(matcher.group(1)));
        }
        return amounts;
    }
}
