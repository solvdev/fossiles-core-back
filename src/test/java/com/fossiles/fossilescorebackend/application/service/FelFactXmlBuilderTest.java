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

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document);

        assertThat(xml).contains("Tipo=\"FACT\"");
        assertThat(xml).contains("IDReceptor=\"CF\"");
        assertThat(xml).contains("NombreReceptor=\"CONSUMIDOR FINAL\"");
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

        String xml = new FelFactXmlBuilder(props).buildUnsignedXml(document);

        assertThat(xml).contains("CodigoEstablecimiento=\"46\"");
        assertThat(xml).doesNotContain("CodigoEstablecimiento=\"1\"");
    }
}
