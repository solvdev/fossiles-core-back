package com.fossiles.fossilescorebackend.application.util;

import java.util.Locale;

/** Herraje en distribución / stock kiosco: NUEVO o VIEJO. */
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

    public static String label(String value) {
        String n = normalize(value);
        if (NUEVO.equals(n)) {
            return "Herraje nuevo";
        }
        if (VIEJO.equals(n)) {
            return "Herraje viejo";
        }
        return "—";
    }
}
