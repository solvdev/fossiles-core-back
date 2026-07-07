package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

public final class ProductCinchoType {

    public static final String CASUAL = "CASUAL";
    public static final String REVERSIBLE = "REVERSIBLE";

    private ProductCinchoType() {
    }

    public static String normalizeCinchoType(String value) {
        String normalized = safeUpper(value);
        if (CASUAL.equals(normalized) || REVERSIBLE.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    public static boolean isValidCinchoType(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = safeUpper(value);
        return CASUAL.equals(normalized) || REVERSIBLE.equals(normalized);
    }

    public static boolean isPackagingProductCode(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return false;
        }
        String normalized = productCode.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("SUM-") || normalized.startsWith("SUM");
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
