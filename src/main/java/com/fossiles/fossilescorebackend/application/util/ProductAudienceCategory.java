package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

public final class ProductAudienceCategory {

    public static final String DAMA = "DAMA";
    public static final String CABALLERO = "CABALLERO";
    public static final String UNISEX = "UNISEX";

    private ProductAudienceCategory() {
    }

    public static String normalizeProductAudience(String value) {
        String normalized = safeUpper(value);
        if (DAMA.equals(normalized) || CABALLERO.equals(normalized)) {
            return normalized;
        }
        return UNISEX;
    }

    public static String normalizePromotionAudience(String value) {
        String normalized = safeUpper(value);
        if (DAMA.equals(normalized) || CABALLERO.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    public static String normalizeTierAudience(String value) {
        String normalized = safeUpper(value);
        if (DAMA.equals(normalized) || CABALLERO.equals(normalized) || UNISEX.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    public static boolean isValidProductAudience(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = safeUpper(value);
        return DAMA.equals(normalized) || CABALLERO.equals(normalized) || UNISEX.equals(normalized);
    }

    public static boolean productMatchesPromotion(String productAudience, String promotionAudience) {
        String promo = normalizePromotionAudience(promotionAudience);
        if (promo == null) {
            return true;
        }
        String product = normalizeProductAudience(productAudience);
        if (UNISEX.equals(product)) {
            return true;
        }
        return promo.equals(product);
    }

    public static boolean productMatchesCatalogFilter(String productAudience, String filterAudience) {
        String filter = normalizePromotionAudience(filterAudience);
        if (filter == null) {
            return true;
        }
        String product = normalizeProductAudience(productAudience);
        if (UNISEX.equals(product)) {
            return true;
        }
        return filter.equals(product);
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
