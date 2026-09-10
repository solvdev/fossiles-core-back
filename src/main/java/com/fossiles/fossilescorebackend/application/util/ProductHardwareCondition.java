package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

/** Dimensión de stock kiosco: herraje NUEVO/VIEJO, o marca en Entre Cueros. */
public final class ProductHardwareCondition {

    public static final String NUEVO = "NUEVO";
    public static final String VIEJO = "VIEJO";

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
     * Dimensión de fila de stock: herraje NUEVO/VIEJO, o marca (LEVIS, NAUTICA, …).
     */
    public static String normalizeStockDimension(String value) {
        String hardware = normalize(value);
        if (hardware != null) {
            return hardware;
        }
        if (value == null || value.isBlank()) {
            return NUEVO;
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static String label(String value) {
        String n = normalize(value);
        if (NUEVO.equals(n)) {
            return "Herraje nuevo";
        }
        if (VIEJO.equals(n)) {
            return "Herraje viejo";
        }
        String dimension = normalizeStockDimension(value);
        if (NUEVO.equals(dimension)) {
            return "Herraje nuevo";
        }
        return dimension;
    }
}
