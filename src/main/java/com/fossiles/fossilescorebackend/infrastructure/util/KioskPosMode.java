package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;

import java.util.Locale;

public final class KioskPosMode {

    public static final String STANDARD = "STANDARD";
    public static final String ENTRECUEROS = "ENTRECUEROS";

    private KioskPosMode() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return STANDARD;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ENTRECUEROS.equals(value)) {
            return ENTRECUEROS;
        }
        return STANDARD;
    }

    public static boolean isEntrecueros(String raw) {
        return ENTRECUEROS.equals(normalize(raw));
    }

    public static boolean isEntrecueros(LocationEntity kiosk) {
        return kiosk != null && isEntrecueros(kiosk.getPosMode());
    }
}
