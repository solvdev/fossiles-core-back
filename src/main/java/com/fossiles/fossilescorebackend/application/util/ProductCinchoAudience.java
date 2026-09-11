package com.fossiles.fossilescorebackend.application.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Cinchos Entre Cueros: dimensión de stock niño / niña. */
public final class ProductCinchoAudience {

    public static final String NINO = "NINO";
    public static final String NINA = "NINA";

    public static final List<String> OPTIONS = List.of(NINO, NINA);
    public static final List<String> ENTRECUEROS_SIZES =
            List.of("16", "18", "20", "22", "24", "26", "28", "30", "32");

    private ProductCinchoAudience() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String n = stripDiacritics(value.trim().toUpperCase(Locale.ROOT)).replaceAll("\\s+", "");
        if (NINO.equals(n) || NINA.equals(n)) {
            return n;
        }
        return null;
    }

    public static String label(String value) {
        String n = normalize(value);
        if (NINO.equals(n)) {
            return "Niño";
        }
        if (NINA.equals(n)) {
            return "Niña";
        }
        return null;
    }

    private static String stripDiacritics(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
