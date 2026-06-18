package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Productos de mesa cinchos: cinchos FOSS / nombre con "cincho", o pulsera(s) por nombre.
 */
public final class CinchoProductUtils {

    public static final String WORK_STATUS_PENDING = "PENDING";
    public static final String WORK_STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String WORK_STATUS_COMPLETED = "COMPLETED";

    private CinchoProductUtils() {}

    /** Cincho clásico (FOSS o nombre con cincho). */
    public static boolean isFossCinchoProduct(ProductEntity product) {
        if (product == null) {
            return false;
        }
        if (isFossCinchoCode(product.getCode())) {
            return true;
        }
        return nameIndicatesCincho(product.getName());
    }

    /** Mesa cinchos del día: cinchos + pulseras. */
    public static boolean isMesaCinchosProduct(ProductEntity product) {
        if (product == null) {
            return false;
        }
        return isFossCinchoProduct(product) || nameIndicatesPulsera(product.getName());
    }

    public static boolean isFossCinchoCode(String code) {
        if (code == null) {
            return false;
        }
        return code.toUpperCase(Locale.ROOT).startsWith("FOSS");
    }

    private static boolean nameIndicatesCincho(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = normalizeName(name);
        return n.contains("cincho");
    }

    public static boolean nameIndicatesPulsera(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return normalizeName(name).contains("pulsera");
    }

    private static String normalizeName(String name) {
        return Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
