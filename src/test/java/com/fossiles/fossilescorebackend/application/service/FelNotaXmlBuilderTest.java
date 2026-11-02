package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** XML de Notas de Crédito (NCRE) y Débito (NDEB) con complemento ReferenciasNota. */
class FelNotaXmlBuilderTest {

    private static final String ORIGIN_UUID = "3623dfd6-22ff-4de9-b164-95c478deff71";

    private static FelEmissionProperties props(String establishment) {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        props.setNombreEmisor("CUEROGLAM, SOCIEDAD ANONIMA");
        props.setNombreComercial("CUEROGLAM");
        props.setDireccion("Zona 4 Guatemala");
        props.setAfiliacionIva("GEN");
        props.setCodigoEstablecimiento(establishment);
        FelEmissionProperties.Frase isr = new FelEmissionProperties.Frase();
        isr.setTipo(1);
        isr.setEscenario(1);
        props.setFrases(List.of(isr));
        return props;
    }

    private static TaxInvoiceDocument note(String type, String establishment, String reason) {
        return TaxInvoiceDocument.builder()
                .transactionId("NCRE-A1-241-15")
                .internalNumber("NCRE-A1-241-15")
                .issuedAt(LocalDateTime.of(2026, 11, 2, 9, 15))
                .documentType(type)
                .emitterEstablishmentCode(establishment)
                .customerTaxId("1234567-8")
                .customerName("CLIENTE PRUEBA SA")
                .subtotal(new BigDecimal("112.00"))
                .totalAmount(new BigDecimal("112.00"))
                .lines(List.of(TaxInvoiceDocument.Line.builder()
                        .description("Devolución cincho B-1")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("112.00"))
                        .lineTotal(new BigDecimal("112.00"))
                        .bundlePriced(true)
                        .build()))
                .noteReference(TaxInvoiceDocument.NoteReference.builder()
                        .originAuthorizationNumber(ORIGIN_UUID)
                        .originSerie("3623DFD6")
                        .originNumero("587091433")
                        .originEmissionDate(LocalDate.of(2026, 10, 30))
                        .adjustmentReason(reason)
                        .build())
                .build();
    }

    private static String build(TaxInvoiceDocument document, String establishment) {
        FelEmissionProperties props = props(establishment);
        return new FelFactXmlBuilder(props).buildUnsignedXml(document, props.resolveCredentials(false));
    }

    @Test
    void ncreIncludesReferenciasNotaWithOriginData() {
        String xml = build(note("NCRE", "2", "Devolución de mercadería"), "2");

        assertThat(xml).contains("Tipo=\"NCRE\"");
        assertThat(xml).contains("URIComplemento=\"http://www.sat.gob.gt/face2/ComplementoReferenciaNota/0.1.0\"");
        assertThat(xml).contains("<cno:ReferenciasNota xmlns:cno=\"http://www.sat.gob.gt/face2/ComplementoReferenciaNota/0.1.0\"");
        assertThat(xml).contains("NumeroAutorizacionDocumentoOrigen=\"3623DFD6-22FF-4DE9-B164-95C478DEFF71\"");
        assertThat(xml).contains("SerieDocumentoOrigen=\"3623DFD6\"");
        assertThat(xml).contains("NumeroDocumentoOrigen=\"587091433\"");
        assertThat(xml).contains("FechaEmisionDocumentoOrigen=\"2026-10-30\"");
        assertThat(xml).contains("MotivoAjuste=\"Devolución de mercadería\"");
        assertThat(xml).contains("Version=\"0.0\"");
        assertThat(xml).contains("IDReceptor=\"12345678\"");
        assertThat(xml).contains("<dte:GranTotal>112.00</dte:GranTotal>");
        // SAT 2.6: la frase ISR tipo 1 es requerida también en NCRE/NDEB.
        assertThat(xml).contains("<dte:Frase CodigoEscenario=\"1\" TipoFrase=\"1\"/>");
        assertThat(xml).doesNotContain("AbonosFacturaCambiaria");
        // Notas no ajustan unitario por paquete aunque la línea venga marcada.
        assertThat(xml).contains("<dte:Descuento>0.00</dte:Descuento>");
        assertWellFormed(xml);
    }

    @Test
    void ndebIncludesReferenciasNota() {
        String xml = build(note("NDEB", "2", "Recargo por envío"), "2");

        assertThat(xml).contains("Tipo=\"NDEB\"");
        assertThat(xml).contains("<cno:ReferenciasNota");
        assertThat(xml).contains("MotivoAjuste=\"Recargo por envío\"");
        assertThat(xml).contains("NumeroAutorizacionDocumentoOrigen=\"3623DFD6-22FF-4DE9-B164-95C478DEFF71\"");
        assertWellFormed(xml);
    }

    @Test
    void notesKeepTheirTypeAtFcamEstablishment() {
        TaxInvoiceDocument document = note("NCRE", "1", "Descuento posterior");
        String xml = build(document, "1");

        assertThat(xml).contains("Tipo=\"NCRE\"");
        assertThat(xml).doesNotContain("Tipo=\"FCAM\"");
        assertThat(xml).doesNotContain("AbonosFacturaCambiaria");
        assertThat(document.getDocumentType()).isEqualTo("NCRE");
        assertThat(FelFactXmlBuilder.resolveEmissionDocumentType("ndeb", "1")).isEqualTo("NDEB");
        assertThat(FelFactXmlBuilder.resolveEmissionDocumentType("FACT", "1")).isEqualTo("FCAM");
    }

    @Test
    void escapesAndTruncatesMotivoAjuste() {
        String longReason = "A&B <devolución> \"parcial\" " + "x".repeat(400);
        String xml = build(note("NCRE", "2", longReason), "2");

        assertThat(xml).contains("MotivoAjuste=\"A&amp;B &lt;devolución&gt; &quot;parcial&quot; ");
        String motivo = xml.substring(xml.indexOf("MotivoAjuste=\"") + 14);
        motivo = motivo.substring(0, motivo.indexOf('"'));
        assertThat(motivo.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""))
                .hasSizeLessThanOrEqualTo(FelFactXmlBuilder.MOTIVO_AJUSTE_MAX_LENGTH);
        assertWellFormed(xml);
    }

    @Test
    void noteWithoutReferenceIsRejected() {
        TaxInvoiceDocument document = note("NCRE", "2", "Devolución");
        document.setNoteReference(null);

        assertThatThrownBy(() -> build(document, "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factura origen");
    }

    @Test
    void noteWithoutReasonIsRejected() {
        assertThatThrownBy(() -> build(note("NDEB", "2", "  "), "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    void noteWithinTwoMonthsHasNoLateFrase() {
        String xml = build(note("NCRE", "2", "Devolución"), "2");

        assertThat(xml).doesNotContain("TipoFrase=\"9\"");
    }

    @Test
    void noteAfterTwoMonthsIncludesFrase9Escenario22() {
        TaxInvoiceDocument document = note("NCRE", "2", "Devolución factura origen mayor a dos meses");
        document.getNoteReference().setOriginEmissionDate(LocalDate.of(2026, 7, 15));
        String xml = build(document, "2");

        assertThat(xml).contains("<dte:Frase CodigoEscenario=\"1\" TipoFrase=\"1\"/>"
                + "<dte:Frase CodigoEscenario=\"22\" TipoFrase=\"9\"/>");
        assertWellFormed(xml);

        TaxInvoiceDocument debit = note("NDEB", "2", "Recargo");
        debit.getNoteReference().setOriginEmissionDate(LocalDate.of(2026, 7, 15));
        assertThat(build(debit, "2")).contains("<dte:Frase CodigoEscenario=\"22\" TipoFrase=\"9\"/>");
    }

    @Test
    void lateNoteBoundaryIsTwoCalendarMonths() {
        ZonedDateTime noteDate = ZonedDateTime.of(2026, 11, 2, 9, 15, 0, 0, ZoneId.of("America/Guatemala"));
        assertThat(FelFactXmlBuilder.isLateNote(LocalDate.of(2026, 9, 2), noteDate)).isFalse();
        assertThat(FelFactXmlBuilder.isLateNote(LocalDate.of(2026, 9, 1), noteDate)).isTrue();
        assertThat(FelFactXmlBuilder.isLateNote(null, noteDate)).isFalse();
    }

    @Test
    void frase9Escenario22NeverGoesOnFacturas() {
        FelEmissionProperties.Frase late = new FelEmissionProperties.Frase();
        late.setTipo(9);
        late.setEscenario(22);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Guatemala"));

        assertThat(FelFactXmlBuilder.resolveFrases(List.of(late), "FACT", null, now)).isEmpty();
    }

    @Test
    void factDoesNotIncludeReferenciasNota() {
        TaxInvoiceDocument document = note("FACT", "2", "no aplica");
        String xml = build(document, "2");

        assertThat(xml).contains("Tipo=\"FACT\"");
        assertThat(xml).doesNotContain("ReferenciasNota");
    }

    private static void assertWellFormed(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError("XML mal formado: " + ex.getMessage(), ex);
        }
    }
}
