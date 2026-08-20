package com.fossiles.fossilescorebackend.infrastructure.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Fecha/hora operativa del negocio (POS, producción, FEL) en zona Guatemala.
 * Evita usar la zona del servidor (p. ej. UTC en AWS).
 */
public final class GuatemalaDateTime {

    public static final ZoneId ZONE = ZoneId.of("America/Guatemala");

    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private GuatemalaDateTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /**
     * Parsea {@code yyyy-MM-dd} o {@code yyyy-MM-ddTHH:mm[:ss]} sin offset (wall-clock GT).
     * Si solo viene fecha: inicio de día (00:00) o fin de día (23:59:59) según {@code endOfDayIfDateOnly}.
     */
    public static LocalDateTime parseFlexible(String raw, boolean endOfDayIfDateOnly) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() == 10) {
            LocalDate date = LocalDate.parse(text);
            return endOfDayIfDateOnly
                    ? LocalDateTime.of(date, LocalTime.of(23, 59, 59))
                    : date.atStartOfDay();
        }
        // datetime-local suele mandar sin segundos
        if (text.length() == 16 && text.charAt(10) == 'T') {
            text = text + ":00";
        }
        try {
            return LocalDateTime.parse(text, LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            return LocalDateTime.parse(text);
        }
    }

    /** Límite exclusivo del ledger: incluye el segundo elegido por el usuario. */
    public static LocalDateTime exclusiveAfterInclusive(LocalDateTime inclusive) {
        if (inclusive == null) {
            return null;
        }
        return inclusive.plusSeconds(1);
    }
}
