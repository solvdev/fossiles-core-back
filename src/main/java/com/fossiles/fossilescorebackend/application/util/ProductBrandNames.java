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
}
