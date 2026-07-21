package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reglas para inicializar stock kiosko: variantes por color y tallas de cincho en cero.
 */
public final class KioscoInventoryInitRules {

    /** Cinchos: CAFE, NEGRO, GENA, NEGRO/CAFE, NEGRO/GENA, CAFE/GENA. */
    public static final List<Long> CINCHO_COLOR_IDS = List.of(2L, 3L, 13L, 37L, 38L, 39L);

    /** Solo aplican a cinchos; el resto del catálogo no los usa. */
    public static final Set<Long> CINCHO_COMBO_ONLY_COLOR_IDS = Set.of(37L, 38L, 39L);

    /** Niño: 16–30 por pares. */
    public static final List<String> KIDS_CINCHO_SIZES = List.of("16", "18", "20", "22", "24", "26", "28", "30");

    /** Dama / caballero: 30–42 por pares. */
    public static final List<String> ADULT_CINCHO_SIZES = List.of("30", "32", "34", "36", "38", "40", "42");

    private KioscoInventoryInitRules() {
    }

    public static boolean isPackagingProduct(ProductEntity product) {
        return product != null && ProductCinchoType.isPackagingProductCode(product.getCode());
    }

    public static boolean isCinchoProduct(ProductEntity product) {
        if (product == null || isPackagingProduct(product)) {
            return false;
        }
        if (ProductCinchoType.normalizeCinchoType(product.getCinchoType()) != null) {
            return true;
        }
        return CinchoProductUtils.isFossCinchoProduct(product);
    }

    public static List<Long> resolveColorIds(ProductEntity product, List<Long> catalogColorIds) {
        if (isPackagingProduct(product)) {
            throw new IllegalArgumentException(
                    "Empaques SUM- no usan variantes por color; inicialícelos aparte.");
        }
        if (isCinchoProduct(product)) {
            return CINCHO_COLOR_IDS.stream()
                    .filter(catalogColorIds::contains)
                    .collect(Collectors.toList());
        }
        return catalogColorIds.stream()
                .filter(id -> !CINCHO_COMBO_ONLY_COLOR_IDS.contains(id))
                .collect(Collectors.toList());
    }

    public static List<String> resolveCinchoSizes(ProductEntity product) {
        if (Boolean.TRUE.equals(product.getCinchoForKids())) {
            return KIDS_CINCHO_SIZES;
        }
        return ADULT_CINCHO_SIZES;
    }

    public static String buildZeroSizesData(ProductEntity product) {
        if (!isCinchoProduct(product)) {
            return null;
        }
        Map<String, BigDecimal> sizes = new LinkedHashMap<>();
        for (String size : resolveCinchoSizes(product)) {
            sizes.put(size, BigDecimal.ZERO);
        }
        return ProductInventorySizesJson.serializeIncludingZeros(sizes);
    }

    public static String stockInitKey(Long locationId, Long productId, Long colorId, String hardwareCondition) {
        String hw = ProductHardwareCondition.normalize(hardwareCondition);
        if (hw == null) {
            hw = ProductHardwareCondition.NUEVO;
        }
        return stockColorKey(locationId, productId, colorId) + "|" + hw;
    }

    /** Clave sin herraje — evita duplicar filas legacy al reinicializar. */
    public static String stockColorKey(Long locationId, Long productId, Long colorId) {
        return locationId + "|" + productId + "|" + (colorId != null ? colorId : "");
    }
}
