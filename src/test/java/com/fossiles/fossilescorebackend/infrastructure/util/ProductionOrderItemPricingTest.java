package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

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

    @Test
    void resolveForSize_appliesCinchoSizeSurchargeOnBasePrice() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(new BigDecimal("250.00"))
                .quantity(1)
                .build();

        assertThat(ProductionOrderItemPricing.resolveForSize(item, "42", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("250.00");
        assertThat(ProductionOrderItemPricing.resolveForSize(item, "46", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("300.00");
        assertThat(ProductionOrderItemPricing.resolveForSize(item, "48", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("300.00");
        assertThat(ProductionOrderItemPricing.resolveForSize(item, "50", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("350.00");
    }

    @Test
    void resolveForSize_appliesSurchargeOnCatalogFallback() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(BigDecimal.ZERO)
                .quantity(1)
                .build();

        assertThat(ProductionOrderItemPricing.resolveForSize(
                item, "50", id -> new BigDecimal("100.00")))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void resolveForSize_explicitUnitPricesJsonDoesNotDoubleCharge() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(new BigDecimal("250.00"))
                .unitPricesJson(ProductionOrderItemPricing.serializeUnitPrices(Map.of(
                        "50", new BigDecimal("350.00"))))
                .quantity(1)
                .build();

        assertThat(ProductionOrderItemPricing.resolveForSize(item, "50", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("350.00");
        // Talla sin override en el mapa: usa unit_price + recargo.
        assertThat(ProductionOrderItemPricing.resolveForSize(item, "48", id -> BigDecimal.ZERO))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void itemSubtotal_appliesSurchargePerSize() {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productId(10L)
                .unitPrice(new BigDecimal("100.00"))
                .sizesData(ProductInventorySizesJson.serialize(Map.of(
                        "42", BigDecimal.ONE,
                        "48", BigDecimal.ONE,
                        "50", BigDecimal.ONE)))
                .quantity(3)
                .build();

        // 100 + 150 + 200 = 450
        assertThat(ProductionOrderItemPricing.itemSubtotal(item, id -> BigDecimal.ZERO))
                .isEqualByComparingTo("450.00");
    }
}
