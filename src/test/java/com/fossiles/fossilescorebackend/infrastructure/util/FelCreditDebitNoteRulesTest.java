package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FelCreditDebitNoteRulesTest {

    private static TaxInvoiceEntity note(long id, String type, String status, String total) {
        return TaxInvoiceEntity.builder()
                .id(id)
                .documentType(type)
                .status(status)
                .originInvoiceId(1L)
                .totalAmount(new BigDecimal(total))
                .build();
    }

    private static TaxInvoiceEntity certifiedFact(String taxId) {
        return TaxInvoiceEntity.builder()
                .id(1L)
                .documentType("FACT")
                .status("CERTIFIED")
                .customerTaxId(taxId)
                .felUuid("3623DFD6-22FF-4DE9-B164-95C478DEFF71")
                .felSerie("3623DFD6")
                .felNumero("587091433")
                .totalAmount(new BigDecimal("500.00"))
                .build();
    }

    @Test
    void balanceSubtractsCertifiedCreditsAndAddsCertifiedDebits() {
        List<TaxInvoiceEntity> notes = List.of(
                note(10, "NCRE", "CERTIFIED", "100.00"),
                note(11, "NDEB", "CERTIFIED", "25.00"),
                note(12, "NCRE", "VOID", "300.00"),
                note(13, "NCRE", "FAILED", "50.00"));

        assertThat(FelCreditDebitNoteRules.remainingBalance(new BigDecimal("500.00"), notes, null))
                .isEqualByComparingTo("425.00");
        // Al reintentar la nota 10 no cuenta contra su propio saldo.
        assertThat(FelCreditDebitNoteRules.remainingBalance(new BigDecimal("500.00"), notes, 10L))
                .isEqualByComparingTo("525.00");
    }

    @Test
    void creditNoteCannotExceedBalance() {
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertNoteAmountAllowed(
                "NCRE", new BigDecimal("400.01"), new BigDecimal("500.00"), new BigDecimal("400.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("saldo");
        assertThatCode(() -> FelCreditDebitNoteRules.assertNoteAmountAllowed(
                "NCRE", new BigDecimal("400.00"), new BigDecimal("500.00"), new BigDecimal("400.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void creditNoteCannotExceedOriginTotalEvenWithDebits() {
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertNoteAmountAllowed(
                "NCRE", new BigDecimal("520.00"), new BigDecimal("500.00"), new BigDecimal("525.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("total de la factura");
    }

    @Test
    void debitNoteHasNoBalanceCapButMustBePositive() {
        assertThatCode(() -> FelCreditDebitNoteRules.assertNoteAmountAllowed(
                "NDEB", new BigDecimal("900.00"), new BigDecimal("500.00"), BigDecimal.ZERO))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertNoteAmountAllowed(
                "NDEB", BigDecimal.ZERO, new BigDecimal("500.00"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void originMustBeCertifiedFacturaWithIdentifiedReceptor() {
        assertThatCode(() -> FelCreditDebitNoteRules.assertOriginAllowsNotes(certifiedFact("12345678")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertOriginAllowsNotes(certifiedFact("CF")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Consumidor Final");

        TaxInvoiceEntity draft = certifiedFact("12345678");
        draft.setStatus("DRAFT");
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertOriginAllowsNotes(draft))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("certificada");

        TaxInvoiceEntity noteAsOrigin = certifiedFact("12345678");
        noteAsOrigin.setDocumentType("NCRE");
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertOriginAllowsNotes(noteAsOrigin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FACT/FCAM");

        TaxInvoiceEntity withoutSerie = certifiedFact("12345678");
        withoutSerie.setFelSerie(null);
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertOriginAllowsNotes(withoutSerie))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void receptorMustMatchOrigin() {
        assertThatCode(() -> FelCreditDebitNoteRules.assertSameReceptor("1234567-8", "12345678"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> FelCreditDebitNoteRules.assertSameReceptor("12345678", "87654321"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void extractsEstablishmentCodeFromCertifiedXml() {
        String xml = "<dte:Emisor AfiliacionIVA=\"GEN\" CodigoEstablecimiento=\"46\" NITEmisor=\"11700874K\">";
        assertThat(FelCreditDebitNoteRules.extractEstablishmentCode(xml)).isEqualTo("46");
        assertThat(FelCreditDebitNoteRules.extractEstablishmentCode(null)).isNull();
        assertThat(FelCreditDebitNoteRules.extractEstablishmentCode("<x/>")).isNull();
    }

    @Test
    void activeNotesAreOnlyCertifiedOnes() {
        assertThat(FelCreditDebitNoteRules.hasActiveNotes(List.of(note(1, "NCRE", "VOID", "1.00")))).isFalse();
        assertThat(FelCreditDebitNoteRules.hasActiveNotes(List.of(note(1, "NDEB", "CERTIFIED", "1.00")))).isTrue();
    }
}
