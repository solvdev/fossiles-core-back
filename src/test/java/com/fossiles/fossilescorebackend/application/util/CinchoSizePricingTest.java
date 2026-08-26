package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CinchoSizePricingTest {

    @Test
    void surcharge_below44_isZero() {
        assertThat(CinchoSizePricing.surchargeForSize("30")).isEqualByComparingTo("0.00");
        assertThat(CinchoSizePricing.surchargeForSize("42")).isEqualByComparingTo("0.00");
        assertThat(CinchoSizePricing.surchargeForSize(null)).isEqualByComparingTo("0.00");
        assertThat(CinchoSizePricing.surchargeForSize("")).isEqualByComparingTo("0.00");
        assertThat(CinchoSizePricing.surchargeForSize("XL")).isEqualByComparingTo("0.00");
    }

    @Test
    void surcharge_44to48_is50() {
        assertThat(CinchoSizePricing.surchargeForSize("44")).isEqualByComparingTo("50.00");
        assertThat(CinchoSizePricing.surchargeForSize("46")).isEqualByComparingTo("50.00");
        assertThat(CinchoSizePricing.surchargeForSize("48")).isEqualByComparingTo("50.00");
    }

    @Test
    void surcharge_50Plus_is100() {
        assertThat(CinchoSizePricing.surchargeForSize("50")).isEqualByComparingTo("100.00");
        assertThat(CinchoSizePricing.surchargeForSize("60")).isEqualByComparingTo("100.00");
    }

    @Test
    void applySurcharge_addsToCatalogPrice() {
        BigDecimal base = new BigDecimal("250.00");
        assertThat(CinchoSizePricing.applySurcharge(base, "42")).isEqualByComparingTo("250.00");
        assertThat(CinchoSizePricing.applySurcharge(base, "48")).isEqualByComparingTo("300.00");
        assertThat(CinchoSizePricing.applySurcharge(base, "50")).isEqualByComparingTo("350.00");
        assertThat(CinchoSizePricing.applySurcharge(BigDecimal.ZERO, "50")).isEqualByComparingTo("0.00");
    }
}
