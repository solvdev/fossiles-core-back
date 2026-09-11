package com.fossiles.fossilescorebackend.application.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Marcas de artículos OPV MARCAS / inventario inicial Entre Cueros. */
public final class ProductBrandNames {

    public static final List<String> OPTIONS = List.of(
            "LEVIS",
            "NAUTICA",
            "TOMMY HILFIGER",
            "LACOSTE",
            "ABERCROMBIE"
    );

    private static final Set<String> OPTION_SET = Set.copyOf(OPTIONS);

    private ProductBrandNames() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String n = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return OPTION_SET.contains(n) ? n : null;
    }

    /** Marca o niño/niña; null si es herraje NUEVO/VIEJO. */
    public static String resolveDistinctDimension(String hardwareCondition) {
        String brand = normalize(hardwareCondition);
        if (brand != null) {
            return brand;
        }
        return ProductCinchoAudience.normalize(hardwareCondition);
    }

    /** Clave de fila de conteo: producto+color, o producto+color+marca/niño-niña. */
    public static String countVariantKey(Long productId, Long colorId, String hardwareCondition) {
        String base = (productId != null ? productId : "") + ":" + (colorId != null ? colorId : "");
        String dimension = resolveDistinctDimension(hardwareCondition);
        return dimension != null ? base + ":" + dimension : base;
    }

    public static String resolveCountHardware(String raw) {
        String dimension = resolveDistinctDimension(raw);
        return dimension != null ? dimension : "NUEVO";
    }
}
