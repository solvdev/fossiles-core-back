package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionOrderItemPricingTest {

    @Test
    void resolveForSize_zeroUnitPriceFallsBackToCatalog() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(BigDecimal.ZERO)
                .quantity(2)
                .build();

        BigDecimal price = ProductionOrderItemPricing.resolveForSize(
                item, null, id -> new BigDecimal("45.00"));

        assertThat(price).isEqualByComparingTo("45.00");
    }

    @Test
    void itemSubtotal_usesCatalogWhenUnitPriceIsZero() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(BigDecimal.ZERO)
                .quantity(3)
                .build();

        BigDecimal subtotal = ProductionOrderItemPricing.itemSubtotal(
                item, id -> new BigDecimal("20.00"));

        assertThat(subtotal).isEqualByComparingTo("60.00");
    }

    @Test
    void resolveForSize_keepsExplicitPositiveUnitPrice() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(new BigDecimal("12.50"))
                .quantity(1)
                .build();

        BigDecimal price = ProductionOrderItemPricing.resolveForSize(
                item, null, id -> new BigDecimal("99.00"));

        assertThat(price).isEqualByComparingTo("12.50");
    }
}
