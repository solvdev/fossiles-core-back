package com.fossiles.fossilescorebackend.infrastructure.util;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * FEL devuelve personas naturales como {@code APELLIDO1,APELLIDO2,,NOMBRE1,NOMBRE2}
 * y empresas como {@code RAZON, SOCIAL} — normaliza a texto legible para factura.
 */
public final class FelTaxpayerNameFormatter {

    private FelTaxpayerNameFormatter() {
    }

    public static String format(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        // Persona natural: apellidos,,nombres (doble coma separa bloques en SAT/FEL)
        if (trimmed.contains(",,")) {
            String[] sections = trimmed.split(",,", -1);
            String apellidos = joinCommaParts(sections[0]);
            String nombres = sections.length > 1 ? joinCommaParts(sections[1]) : "";
            if (!nombres.isEmpty() && !apellidos.isEmpty()) {
                return nombres + " " + apellidos;
            }
            return !nombres.isEmpty() ? nombres : apellidos;
        }

        return joinCommaParts(trimmed);
    }

    private static String joinCommaParts(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
    }
}
