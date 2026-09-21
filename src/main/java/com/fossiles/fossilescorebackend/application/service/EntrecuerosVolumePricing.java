package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class EntrecuerosVolumePricing {

    private EntrecuerosVolumePricing() {
    }

    public static BigDecimal resolveUnitPrice(ProductEntity product, BigDecimal quantity) {
        if (product == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        int qty = quantity == null ? 0 : quantity.setScale(0, RoundingMode.DOWN).intValue();
        BigDecimal price12 = positive(product.getEntrecuerosPriceQty12());
        BigDecimal price6 = positive(product.getEntrecuerosPriceQty6());
        BigDecimal price3 = positive(product.getEntrecuerosPriceQty3());
        BigDecimal price1 = positive(product.getEntrecuerosPriceUnit());
        BigDecimal chosen = null;
        if (qty >= 12 && price12 != null) {
            chosen = price12;
        } else if (qty >= 6 && price6 != null) {
            chosen = price6;
        } else if (qty >= 3 && price3 != null) {
            chosen = price3;
        } else {
            chosen = price1;
        }
        if (chosen == null) {
            chosen = positive(product.getSalePrice());
        }
        if (chosen == null) {
            chosen = positive(product.getSellerPrice());
        }
        return (chosen != null ? chosen : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
