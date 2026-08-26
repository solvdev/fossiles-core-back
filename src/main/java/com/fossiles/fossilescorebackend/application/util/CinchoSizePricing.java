package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Recargo de precio POS por talla de cincho (sobre precio de catálogo):
 * 44–48 → Q50; 50+ → Q100.
 */
public final class CinchoSizePricing {

    public static final BigDecimal SURCHARGE_44_TO_48 = new BigDecimal("50.00");
    public static final BigDecimal SURCHARGE_50_PLUS = new BigDecimal("100.00");

    private CinchoSizePricing() {
    }

    public static BigDecimal surchargeForSize(String size) {
        if (size == null || size.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        try {
            int n = Integer.parseInt(size.trim());
            if (n >= 50) {
                return SURCHARGE_50_PLUS;
            }
            if (n >= 44) {
                return SURCHARGE_44_TO_48;
            }
        } catch (NumberFormatException ignored) {
            // Talla no numérica: sin recargo.
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal applySurcharge(BigDecimal basePrice, String size) {
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return basePrice.add(surchargeForSize(size)).setScale(2, RoundingMode.HALF_UP);
    }
}
