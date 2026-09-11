package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

/** Dimensión de stock kiosco: herraje NUEVO/VIEJO, marca, niño/niña o sintético. */
public final class ProductHardwareCondition {

    public static final String NUEVO = "NUEVO";
    public static final String VIEJO = "VIEJO";
    public static final String SINTETICO = "SINTETICO";
    public static final String SINTETICO_LABEL = "Sintética";

    private ProductHardwareCondition() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String n = value.trim().toUpperCase(Locale.ROOT);
        if (NUEVO.equals(n) || "NEW".equals(n)) {
            return NUEVO;
        }
        if (VIEJO.equals(n) || "OLD".equals(n) || "ANTIGUO".equals(n)) {
            return VIEJO;
        }
        return null;
    }

    /**
     * Dimensión de fila de stock: herraje, marca, niño/niña o SINTETICO.
     */
    public static String normalizeStockDimension(String value) {
        String hardware = normalize(value);
        if (hardware != null) {
            return hardware;
        }
        if (value == null || value.isBlank()) {
            return NUEVO;
        }
        String n = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (isSyntheticToken(n)) {
            return SINTETICO;
        }
        return n;
    }

    public static boolean isSynthetic(String value) {
        return SINTETICO.equals(normalizeStockDimension(value));
    }

    public static String appendSyntheticToName(String name) {
        String n = name != null ? name.trim() : "";
        if (n.isEmpty()) {
            return SINTETICO_LABEL;
        }
        String compact = n.toUpperCase(Locale.ROOT).replace("É", "E").replace("Á", "A");
        if (compact.contains("SINTETIC")) {
            return n;
        }
        return (n + " " + SINTETICO_LABEL).trim();
    }

    public static String label(String value) {
        String n = normalize(value);
        if (NUEVO.equals(n)) {
            return "Herraje nuevo";
        }
        if (VIEJO.equals(n)) {
            return "Herraje viejo";
        }
        String audience = ProductCinchoAudience.label(value);
        if (audience != null) {
            return audience;
        }
        String dimension = normalizeStockDimension(value);
        if (SINTETICO.equals(dimension)) {
            return SINTETICO_LABEL;
        }
        if (NUEVO.equals(dimension)) {
            return "Herraje nuevo";
        }
        return dimension;
    }

    private static boolean isSyntheticToken(String normalized) {
        String compact = normalized
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
        return SINTETICO.equals(compact) || "SINTETICA".equals(compact);
    }
}
