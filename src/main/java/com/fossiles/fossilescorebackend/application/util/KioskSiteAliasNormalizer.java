package com.fossiles.fossilescorebackend.application.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizacion de nombres de columna del Excel (alias de sitio) segun el contrato:
 * NFD, sin marcas diacriticas, MAYUSCULAS, solo A-Z / 0-9 / espacio (elimina U+FFFD), espacios colapsados, trim.
 */
public final class KioskSiteAliasNormalizer {

    private KioskSiteAliasNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replaceAll("\\s+", " ");
        n = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "");
        return n.replaceAll(" +", " ").trim();
    }
}
