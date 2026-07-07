package com.fossiles.fossilescorebackend.infrastructure.util;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Corrige texto FEL leído con encoding incorrecto (p. ej. UTF-8 interpretado como Latin-1).
 */
public final class FelTextEncodingHelper {

    private FelTextEncodingHelper() {
    }

    public static String repairFelText(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String trimmed = value.trim();
        if (!looksLikeMojibake(trimmed)) {
            return Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        }
        try {
            String repaired = new String(trimmed.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return Normalizer.normalize(repaired, Normalizer.Form.NFC);
        } catch (Exception ex) {
            return Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        }
    }

    private static boolean looksLikeMojibake(String value) {
        return value.contains("Ã") || value.contains("Â");
    }
}
