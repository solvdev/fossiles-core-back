package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FelIvaCalculatorTest {

    @Test
    void splitsTaxIncludedTotal() {
        FelIvaCalculator.IvaBreakdown breakdown = FelIvaCalculator.fromTaxIncludedTotal(new BigDecimal("1.00"));
        assertThat(breakdown.gravable()).isEqualByComparingTo("0.89");
        assertThat(breakdown.tax()).isEqualByComparingTo("0.11");
        assertThat(breakdown.gravable().add(breakdown.tax())).isEqualByComparingTo("1.00");
    }
}
