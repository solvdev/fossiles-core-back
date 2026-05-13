package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.util.Locale;

/**
 * Cinchos de catálogo FOSS (venta en línea / PT): código de producto con prefijo FOSS.
 */
public final class CinchoProductUtils {

    private CinchoProductUtils() {}

    public static boolean isFossCinchoProduct(ProductEntity product) {
        if (product == null) {
            return false;
        }
        return isFossCinchoCode(product.getCode());
    }

    public static boolean isFossCinchoCode(String code) {
        if (code == null) {
            return false;
        }
        return code.toUpperCase(Locale.ROOT).startsWith("FOSS");
    }
}
