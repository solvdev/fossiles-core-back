package com.fossiles.fossilescorebackend.application.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Porcentajes y montos legales vigentes en una fecha; vienen de {@code payroll_parameter}. */
public record PayrollParameters(
        BigDecimal igssEmployeePct,
        BigDecimal igssEmployerPct,
        BigDecimal irtraPct,
        BigDecimal intecapPct,
        BigDecimal defaultMonthlyBonification,
        BigDecimal isrPersonalDeduction,
        BigDecimal isrBracketLimit,
        BigDecimal isrRate1Pct,
        BigDecimal isrRate2Pct,
        BigDecimal isrFixedBracket2) {

    public static final String IGSS_LABORAL_PCT = "IGSS_LABORAL_PCT";
    public static final String IGSS_PATRONAL_PCT = "IGSS_PATRONAL_PCT";
    public static final String IRTRA_PCT = "IRTRA_PCT";
    public static final String INTECAP_PCT = "INTECAP_PCT";
    public static final String BONIFICACION_INCENTIVO_MENSUAL = "BONIFICACION_INCENTIVO_MENSUAL";
    public static final String ISR_DEDUCCION_PERSONAL = "ISR_DEDUCCION_PERSONAL";
    public static final String ISR_TRAMO_LIMITE = "ISR_TRAMO_LIMITE";
    public static final String ISR_TASA_TRAMO1_PCT = "ISR_TASA_TRAMO1_PCT";
    public static final String ISR_TASA_TRAMO2_PCT = "ISR_TASA_TRAMO2_PCT";
    public static final String ISR_CUOTA_FIJA_TRAMO2 = "ISR_CUOTA_FIJA_TRAMO2";

    public static final List<String> REQUIRED_CODES = List.of(
            IGSS_LABORAL_PCT, IGSS_PATRONAL_PCT, IRTRA_PCT, INTECAP_PCT, BONIFICACION_INCENTIVO_MENSUAL,
            ISR_DEDUCCION_PERSONAL, ISR_TRAMO_LIMITE, ISR_TASA_TRAMO1_PCT, ISR_TASA_TRAMO2_PCT, ISR_CUOTA_FIJA_TRAMO2);

    /** Falla con la lista de códigos ausentes para que Contabilidad sepa cuáles cargar. */
    public static PayrollParameters from(Map<String, BigDecimal> values) {
        List<String> missing = new ArrayList<>();
        for (String code : REQUIRED_CODES) {
            if (values.get(code) == null) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Faltan parámetros de planilla vigentes: " + String.join(", ", missing));
        }
        return new PayrollParameters(
                values.get(IGSS_LABORAL_PCT), values.get(IGSS_PATRONAL_PCT), values.get(IRTRA_PCT),
                values.get(INTECAP_PCT), values.get(BONIFICACION_INCENTIVO_MENSUAL), values.get(ISR_DEDUCCION_PERSONAL),
                values.get(ISR_TRAMO_LIMITE), values.get(ISR_TASA_TRAMO1_PCT), values.get(ISR_TASA_TRAMO2_PCT),
                values.get(ISR_CUOTA_FIJA_TRAMO2));
    }
}
