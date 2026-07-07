package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
                .transactionId("POS-20260101-001")
                .issuedAt(LocalDateTime.of(2026, 1, 15, 10, 30))
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
    void includesReceptorEmailSemicolonSeparated() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
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

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
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
    void includesInternalControlNumberInAdenda() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("123456789");
        props.setNombreEmisor("FOSSILES SA");
        props.setAfiliacionIva("GEN");

        TaxInvoiceDocument document = TaxInvoiceDocument.builder()
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
}
