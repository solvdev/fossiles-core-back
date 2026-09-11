package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

/** Dimensión de stock kiosco: herraje NUEVO/VIEJO, marca, niño/niña o sintético. */
public final class ProductHardwareCondition {

    public static final String NUEVO = "NUEVO";
    public static final String VIEJO = "VIEJO";
    public static final String SINTETICO = "SINTETICO";
    public static final String SINTETICO_LABEL = "Sintética";
    public static final String NO_SINTETICO = "NO_SINTETICO";
    public static final String NO_SINTETICO_LABEL = "No sintética";

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
        String material = normalizeMaterialToken(n);
        if (material != null) {
            return material;
        }
        return n;
    }

    public static boolean isSynthetic(String value) {
        return SINTETICO.equals(normalizeMaterialToken(value));
    }

    public static boolean isNonSynthetic(String value) {
        return NO_SINTETICO.equals(normalizeMaterialToken(value));
    }

    /**
     * Billeteras Entre Cueros: SINTETICO o NO_SINTETICO.
     * NUEVO / vacío → sintético por defecto. Marca u otro valor → null.
     */
    public static String resolveWalletMaterial(String raw) {
        String material = normalizeMaterialToken(raw);
        if (material != null) {
            return material;
        }
        String hardware = normalize(raw);
        if (hardware == null || NUEVO.equals(hardware)) {
            return SINTETICO;
        }
        return null;
    }

    public static String appendMaterialToName(String name, String hardware) {
        String label = materialLabel(hardware);
        if (label == null) {
            return name != null ? name.trim() : "";
        }
        String n = name != null ? name.trim() : "";
        String compact = compactKey(n).replace(" ", "").replace("_", "");
        if (NO_SINTETICO_LABEL.equals(label)) {
            if (compact.contains("NOSINTETIC")) {
                return n.isEmpty() ? label : n;
            }
        } else if (compact.contains("SINTETIC") && !compact.contains("NOSINTETIC")) {
            return n.isEmpty() ? label : n;
        }
        return (n + " " + label).trim();
    }

    public static String appendSyntheticToName(String name) {
        return appendMaterialToName(name, SINTETICO);
    }

    public static String materialLabel(String value) {
        if (isNonSynthetic(value)) {
            return NO_SINTETICO_LABEL;
        }
        if (isSynthetic(value)) {
            return SINTETICO_LABEL;
        }
        return null;
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
        String material = materialLabel(dimension);
        if (material != null) {
            return material;
        }
        if (NUEVO.equals(dimension)) {
            return "Herraje nuevo";
        }
        return dimension;
    }

    private static String normalizeMaterialToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = compactKey(value).replace(" ", "").replace("_", "");
        if ("NOSINTETICO".equals(compact) || "NOSINTETICA".equals(compact) || "CUERO".equals(compact)) {
            return NO_SINTETICO;
        }
        if (SINTETICO.equals(compact) || "SINTETICA".equals(compact)) {
            return SINTETICO;
        }
        return null;
    }

    private static String compactKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }
}
