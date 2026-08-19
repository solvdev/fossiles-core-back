package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KioskExchangePackagingCreditTest {

    @Test
    void ignoresPackagingWhenProductPricesAreEqual() {
        assertThat(KioskExchangeService.appliedPackagingCredit(
                new BigDecimal("180.00"),
                new BigDecimal("180.00"),
                new BigDecimal("15.00")
        )).isEqualByComparingTo("0.00");
    }

    @Test
    void includesPackagingWhenProductPricesDiffer() {
        assertThat(KioskExchangeService.appliedPackagingCredit(
                new BigDecimal("250.00"),
                new BigDecimal("180.00"),
                new BigDecimal("15.00")
        )).isEqualByComparingTo("15.00");
    }

    @Test
    void includesPackagingWhenGivenProductIsCheaper() {
        assertThat(KioskExchangeService.appliedPackagingCredit(
                new BigDecimal("180.00"),
                new BigDecimal("250.00"),
                new BigDecimal("15.00")
        )).isEqualByComparingTo("15.00");
    }
}
