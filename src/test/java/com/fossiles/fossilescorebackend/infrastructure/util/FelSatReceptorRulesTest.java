package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FelSatReceptorRulesTest {

    private static final LocalDate EMISSION = LocalDate.of(2026, 8, 8);

    @Test
    void isConsumidorFinal_acceptsBlankCfAndSlashVariants() {
        assertThat(FelSatReceptorRules.isConsumidorFinal(null)).isTrue();
        assertThat(FelSatReceptorRules.isConsumidorFinal("")).isTrue();
        assertThat(FelSatReceptorRules.isConsumidorFinal("cf")).isTrue();
        assertThat(FelSatReceptorRules.isConsumidorFinal("C/F")).isTrue();
        assertThat(FelSatReceptorRules.isConsumidorFinal("11700874K")).isFalse();
    }

    @Test
    void directAnnulmentWindow_sameDayAndNextDayAllowed_dayPlusTwoRejected() {
        assertThat(FelSatReceptorRules.isWithinDirectAnnulmentWindow(EMISSION, EMISSION)).isTrue();
        assertThat(FelSatReceptorRules.isWithinDirectAnnulmentWindow(EMISSION, EMISSION.plusDays(1))).isTrue();
        assertThat(FelSatReceptorRules.isWithinDirectAnnulmentWindow(EMISSION, EMISSION.plusDays(2))).isFalse();
        assertThat(FelSatReceptorRules.isWithinDirectAnnulmentWindow(EMISSION, EMISSION.minusDays(1))).isFalse();
    }

    @Test
    void assertDirectAnnulmentAllowed_cfSameDay_ok() {
        TaxInvoiceEntity invoice = certifiedFact("CF", EMISSION.atTime(10, 0));
        assertThatCode(() -> FelSatReceptorRules.assertDirectAnnulmentAllowed(invoice, EMISSION))
                .doesNotThrowAnyException();
    }

    @Test
    void assertDirectAnnulmentAllowed_cfNextDay_ok() {
        TaxInvoiceEntity invoice = certifiedFact("CF", EMISSION.atTime(10, 0));
        assertThatCode(() -> FelSatReceptorRules.assertDirectAnnulmentAllowed(invoice, EMISSION.plusDays(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void assertDirectAnnulmentAllowed_cfDayPlusTwo_throws() {
        TaxInvoiceEntity invoice = certifiedFact("CF", EMISSION.atTime(10, 0));
        assertThatThrownBy(() -> FelSatReceptorRules.assertDirectAnnulmentAllowed(invoice, EMISSION.plusDays(2)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Consumidor Final")
                .hasMessageContaining("2026-08-09");
    }

    @Test
    void assertDirectAnnulmentAllowed_nitLate_ok() {
        TaxInvoiceEntity invoice = certifiedFact("11700874K", EMISSION.atTime(10, 0));
        assertThatCode(() -> FelSatReceptorRules.assertDirectAnnulmentAllowed(invoice, EMISSION.plusDays(10)))
                .doesNotThrowAnyException();
        assertThat(FelSatReceptorRules.isDirectFelVoidAllowed(invoice, EMISSION.plusDays(10))).isTrue();
    }

    @Test
    void isDirectFelVoidAllowed_cfOutsideWindow_false() {
        TaxInvoiceEntity invoice = certifiedFact("CF", EMISSION.atTime(10, 0));
        assertThat(FelSatReceptorRules.isDirectFelVoidAllowed(invoice, EMISSION.plusDays(2))).isFalse();
        assertThat(FelSatReceptorRules.directAnnulmentDeadlineDate(EMISSION)).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    void assertCreditDebitReceptorAllowed_ncreCf_rejected() {
        assertThatThrownBy(() -> FelSatReceptorRules.assertCreditDebitReceptorAllowed("NCRE", "CF"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2.2.4");
    }

    @Test
    void assertCreditDebitReceptorAllowed_ncreNit_allowed() {
        assertThatCode(() -> FelSatReceptorRules.assertCreditDebitReceptorAllowed("NCRE", "11700874K"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCreditDebitReceptorAllowed_ndebCf_rejected() {
        assertThatThrownBy(() -> FelSatReceptorRules.assertCreditDebitReceptorAllowed("NDEB", "c/f"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2.2.4");
    }

    @Test
    void assertCreditDebitReceptorAllowed_factCf_noop() {
        assertThatCode(() -> FelSatReceptorRules.assertCreditDebitReceptorAllowed("FACT", "CF"))
                .doesNotThrowAnyException();
    }

    private static TaxInvoiceEntity certifiedFact(String taxId, LocalDateTime issuedAt) {
        return TaxInvoiceEntity.builder()
                .id(1L)
                .status("CERTIFIED")
                .documentType("FACT")
                .customerTaxId(taxId)
                .felUuid("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
                .issuedAt(issuedAt)
                .build();
    }
}
