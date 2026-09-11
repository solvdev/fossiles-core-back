package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

/** Dimensión de stock kiosco: herraje NUEVO/VIEJO, marca, niño/niña o sintético+marca. */
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
     * Dimensión de fila de stock: herraje, marca, niño/niña o SINTETICO:MARCA.
     */
    public static String normalizeStockDimension(String value) {
        String hardware = normalize(value);
        if (hardware != null) {
            return hardware;
        }
        if (value == null || value.isBlank()) {
            return NUEVO;
        }
        String wallet = resolveWalletDimension(value);
        if (wallet != null) {
            return wallet;
        }
        String n = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String material = normalizeMaterialToken(n);
        if (material != null) {
            return material;
        }
        return n;
    }

    public static boolean isSynthetic(String value) {
        String compact = compactKey(value).replace(" ", "").replace("_", "");
        if (compact.isEmpty() || compact.startsWith("NOSINTETIC")) {
            return false;
        }
        return SINTETICO.equals(compact)
                || "SINTETICA".equals(compact)
                || compact.startsWith(SINTETICO + ":")
                || compact.startsWith("SINTETICA:");
    }

    public static boolean isNonSynthetic(String value) {
        String compact = compactKey(value).replace(" ", "").replace("_", "");
        return "NOSINTETICO".equals(compact) || "NOSINTETICA".equals(compact);
    }

    public static String stripSyntheticPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String n = value.trim();
        int sep = n.indexOf(':');
        if (sep > 0) {
            String prefix = compactKey(n.substring(0, sep)).replace(" ", "").replace("_", "");
            if (SINTETICO.equals(prefix) || "SINTETICA".equals(prefix)
                    || "NOSINTETICO".equals(prefix) || "NOSINTETICA".equals(prefix)) {
                return n.substring(sep + 1).trim();
            }
        }
        String compact = compactKey(n).replace(" ", "").replace("_", "");
        if (SINTETICO.equals(compact) || "SINTETICA".equals(compact)
                || "NOSINTETICO".equals(compact) || "NOSINTETICA".equals(compact)) {
            return "";
        }
        return n;
    }

    /**
     * Billeteras Entre Cueros: marca obligatoria.
     * Sintético → SINTETICO:MARCA. Si no se marca sintético → solo MARCA.
     */
    public static String resolveWalletDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        boolean synthetic = isSynthetic(raw);
        String brand = ProductBrandNames.normalize(stripSyntheticPrefix(raw));
        if (brand == null) {
            return null;
        }
        return synthetic ? SINTETICO + ":" + brand : brand;
    }

    public static String appendMaterialToName(String name, String hardware) {
        String n = name != null ? name.trim() : "";
        if (isSynthetic(hardware)) {
            String compact = compactKey(n).replace(" ", "").replace("_", "");
            if (n.isEmpty()) {
                n = SINTETICO_LABEL;
            } else if (!compact.contains("SINTETIC") || compact.contains("NOSINTETIC")) {
                n = (n + " " + SINTETICO_LABEL).trim();
            }
        }
        String brand = ProductBrandNames.normalize(stripSyntheticPrefix(hardware));
        if (brand != null) {
            String upper = n.toUpperCase(Locale.ROOT);
            if (!upper.contains(brand)) {
                n = (n + " " + brand).trim();
            }
        }
        return n;
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
        boolean synthetic = isSynthetic(value);
        String brand = ProductBrandNames.normalize(stripSyntheticPrefix(value));
        if (synthetic && brand != null) {
            return SINTETICO_LABEL + " · " + brand;
        }
        if (synthetic) {
            return SINTETICO_LABEL;
        }
        if (isNonSynthetic(value)) {
            return brand != null ? NO_SINTETICO_LABEL + " · " + brand : NO_SINTETICO_LABEL;
        }
        if (brand != null) {
            return brand;
        }
        String dimension = normalizeStockDimension(value);
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
        if ("NOSINTETICO".equals(compact) || "NOSINTETICA".equals(compact)) {
            return NO_SINTETICO;
        }
        if (SINTETICO.equals(compact) || "SINTETICA".equals(compact)) {
            return SINTETICO;
        }
        return null;
    }

    private static String compactKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }
}
