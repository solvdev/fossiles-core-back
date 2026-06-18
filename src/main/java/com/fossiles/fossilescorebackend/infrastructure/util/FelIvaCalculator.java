package com.fossiles.fossilescorebackend.infrastructure.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FelIvaCalculator {

    private static final BigDecimal IVA_FACTOR = new BigDecimal("1.12");

    private FelIvaCalculator() {
    }

    public record IvaBreakdown(BigDecimal gravable, BigDecimal tax) {
    }

    /** Precio con IVA incluido → base gravable + IVA (12 %). */
    public static IvaBreakdown fromTaxIncludedTotal(BigDecimal taxIncludedTotal) {
        BigDecimal total = nz(taxIncludedTotal).setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new IvaBreakdown(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal gravable = total.divide(IVA_FACTOR, 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = total.subtract(gravable).setScale(2, RoundingMode.HALF_UP);
        if (tax.compareTo(BigDecimal.ZERO) < 0) {
            tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new IvaBreakdown(gravable, tax);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
