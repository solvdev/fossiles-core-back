package com.fossiles.fossilescorebackend.application.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Cinchos Entre Cueros: dimensión de stock niño / dama (mismas tallas). */
public final class ProductCinchoAudience {

    public static final String NINO = "NINO";
    public static final String DAMA = "DAMA";
    /** Alias legado; se normaliza a DAMA. */
    public static final String NINA = "NINA";

    public static final List<String> OPTIONS = List.of(NINO, DAMA);
    public static final List<String> ENTRECUEROS_SIZES =
            List.of("16", "18", "20", "22", "24", "26", "28", "30", "32");

    private ProductCinchoAudience() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String n = stripDiacritics(value.trim().toUpperCase(Locale.ROOT)).replaceAll("\\s+", "");
        if (NINO.equals(n)) {
            return NINO;
        }
        if (DAMA.equals(n) || NINA.equals(n)) {
            return DAMA;
        }
        return null;
    }

    public static String label(String value) {
        String n = normalize(value);
        if (NINO.equals(n)) {
            return "Niño";
        }
        if (DAMA.equals(n)) {
            return "Dama";
        }
        return null;
    }

    /** En recepción JR: talla menor a 30 es Niño; 30 o mayor es Dama. */
    public static String fromSize(String sizeKey) {
        Integer size = parseSize(sizeKey);
        if (size == null) {
            return null;
        }
        return size >= 30 ? DAMA : NINO;
    }

    private static Integer parseSize(String sizeKey) {
        if (sizeKey == null || sizeKey.isBlank()) {
            return null;
        }
        String digits = sizeKey.trim();
        int i = 0;
        while (i < digits.length() && Character.isDigit(digits.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        try {
            return Integer.parseInt(digits.substring(0, i));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stripDiacritics(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
