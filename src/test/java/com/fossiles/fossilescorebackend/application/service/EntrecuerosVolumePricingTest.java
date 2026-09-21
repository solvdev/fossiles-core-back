package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EntrecuerosVolumePricingTest {

    @Test
    void usesTierPriceByQuantity() {
        ProductEntity product = ProductEntity.builder()
                .entrecuerosEnabled(true)
                .entrecuerosPriceUnit(new BigDecimal("100.00"))
                .entrecuerosPriceQty3(new BigDecimal("90.00"))
                .entrecuerosPriceQty6(new BigDecimal("80.00"))
                .entrecuerosPriceQty12(new BigDecimal("70.00"))
                .build();

        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, BigDecimal.ONE))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("2")))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("3")))
                .isEqualByComparingTo("90.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("5")))
                .isEqualByComparingTo("90.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("6")))
                .isEqualByComparingTo("80.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("11")))
                .isEqualByComparingTo("80.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("12")))
                .isEqualByComparingTo("70.00");
        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("20")))
                .isEqualByComparingTo("70.00");
    }

    @Test
    void fallsBackWhenHigherTierMissing() {
        ProductEntity product = ProductEntity.builder()
                .entrecuerosPriceUnit(new BigDecimal("50.00"))
                .entrecuerosPriceQty3(new BigDecimal("40.00"))
                .build();

        assertThat(EntrecuerosVolumePricing.resolveUnitPrice(product, new BigDecimal("12")))
                .isEqualByComparingTo("40.00");
    }
}
