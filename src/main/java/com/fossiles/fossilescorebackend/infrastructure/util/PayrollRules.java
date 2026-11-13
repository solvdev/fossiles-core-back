package com.fossiles.fossilescorebackend.infrastructure.util;

import java.util.Locale;
import java.util.Set;

/** Valores de la ficha laboral y reglas de grupo confirmadas para la planilla. */
public final class PayrollRules {
    public static final String EMPLOYMENT_PLANILLA = "PLANILLA";
    public static final String EMPLOYMENT_SERVICIOS = "SERVICIOS";
    public static final Set<String> EMPLOYMENT_TYPES = Set.of(EMPLOYMENT_PLANILLA, EMPLOYMENT_SERVICIOS);

    public static final String GROUP_PLANTA = "PLANTA";
    public static final String GROUP_ADMINISTRACION = "ADMINISTRACION";
    public static final Set<String> PAY_GROUPS = Set.of(GROUP_PLANTA, GROUP_ADMINISTRACION);

    public static final String PAYMENT_TRANSFER = "TRANSFERENCIA BANCARIA";
    public static final String PAYMENT_CHEQUE = "CHEQUE";
    public static final String PAYMENT_CASH = "EFECTIVO";
    public static final Set<String> PAYMENT_METHODS = Set.of(PAYMENT_TRANSFER, PAYMENT_CHEQUE, PAYMENT_CASH);

    private PayrollRules() {}

    public static String normalize(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Lleva las variantes escritas a mano ("Transferencia  bancaria", "cheque") al valor canónico; null si no se reconoce. */
    public static String canonicalPaymentMethod(String value) {
        String letters = value == null ? "" : normalize(value).replaceAll("[^A-Z]", "");
        return switch (letters) {
            case "TRANSFERENCIABANCARIA", "TRANSFERENCIA" -> PAYMENT_TRANSFER;
            case "CHEQUE" -> PAYMENT_CHEQUE;
            case "EFECTIVO" -> PAYMENT_CASH;
            default -> null;
        };
    }

    /** La planta de producción cobra el 5 y el 20; los demás departamentos, el 15 y a fin de mes. */
    public static String payGroupForDepartment(String departmentName, String departmentCode) {
        return isProduction(departmentName, departmentCode) ? GROUP_PLANTA : GROUP_ADMINISTRACION;
    }

    public static boolean isProduction(String departmentName, String departmentCode) {
        return contains(departmentName, "PRODUCCI") || contains(departmentCode, "PRODUC");
    }

    public static boolean isKiosk(String departmentName, String departmentCode) {
        return contains(departmentName, "KIOSCO") || contains(departmentCode, "KIOSCO");
    }

    private static boolean contains(String value, String token) {
        return value != null && normalize(value).replace("Ó", "O").contains(token);
    }
}
