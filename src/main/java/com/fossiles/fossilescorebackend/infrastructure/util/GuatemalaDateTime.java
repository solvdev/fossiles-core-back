package com.fossiles.fossilescorebackend.infrastructure.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Fecha/hora operativa del negocio (POS, producción, FEL) en zona Guatemala.
 * Evita usar la zona del servidor (p. ej. UTC en AWS).
 */
public final class GuatemalaDateTime {

    public static final ZoneId ZONE = ZoneId.of("America/Guatemala");

    private GuatemalaDateTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
