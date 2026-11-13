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
        BigDecimal isrFixedBracket2,
        BigDecimal overtimeFactor,
        BigDecimal holidayFactor,
        BigDecimal workdayHours) {

    public static final String HORA_EXTRA_FACTOR = "HORA_EXTRA_FACTOR";
    public static final String FERIADO_TRABAJADO_FACTOR = "FERIADO_TRABAJADO_FACTOR";
    public static final String JORNADA_HORAS = "JORNADA_HORAS";
    static final BigDecimal DEFAULT_OVERTIME_FACTOR = new BigDecimal("1.5");
    static final BigDecimal DEFAULT_HOLIDAY_FACTOR = new BigDecimal("2");
    static final BigDecimal DEFAULT_WORKDAY_HOURS = new BigDecimal("8");

    /** Los diez parámetros de ley; horas extra, feriado y jornada toman su valor de costumbre. */
    public PayrollParameters(BigDecimal igssEmployeePct, BigDecimal igssEmployerPct, BigDecimal irtraPct,
                             BigDecimal intecapPct, BigDecimal defaultMonthlyBonification, BigDecimal isrPersonalDeduction,
                             BigDecimal isrBracketLimit, BigDecimal isrRate1Pct, BigDecimal isrRate2Pct,
                             BigDecimal isrFixedBracket2) {
        this(igssEmployeePct, igssEmployerPct, irtraPct, intecapPct, defaultMonthlyBonification, isrPersonalDeduction,
                isrBracketLimit, isrRate1Pct, isrRate2Pct, isrFixedBracket2,
                DEFAULT_OVERTIME_FACTOR, DEFAULT_HOLIDAY_FACTOR, DEFAULT_WORKDAY_HOURS);
    }

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

    /** Los de recargo pueden faltar: entonces rigen los valores de costumbre. */
    public static final List<String> OPTIONAL_CODES = List.of(HORA_EXTRA_FACTOR, FERIADO_TRABAJADO_FACTOR, JORNADA_HORAS);

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
                values.get(ISR_CUOTA_FIJA_TRAMO2),
                values.getOrDefault(HORA_EXTRA_FACTOR, DEFAULT_OVERTIME_FACTOR),
                values.getOrDefault(FERIADO_TRABAJADO_FACTOR, DEFAULT_HOLIDAY_FACTOR),
                values.getOrDefault(JORNADA_HORAS, DEFAULT_WORKDAY_HOURS));
    }

    public static boolean isKnownCode(String code) {
        return REQUIRED_CODES.contains(code) || OPTIONAL_CODES.contains(code);
    }

    /**
     * Igual que el texto de antes de existir los parámetros de recargo mientras tengan su valor de costumbre: la huella
     * de las planillas en borrador depende de este texto y no debe cambiar por agregar campos.
     */
    @Override
    public String toString() {
        boolean defaults = overtimeFactor.compareTo(DEFAULT_OVERTIME_FACTOR) == 0
                && holidayFactor.compareTo(DEFAULT_HOLIDAY_FACTOR) == 0
                && workdayHours.compareTo(DEFAULT_WORKDAY_HOURS) == 0;
        return "PayrollParameters[igssEmployeePct=" + igssEmployeePct + ", igssEmployerPct=" + igssEmployerPct
                + ", irtraPct=" + irtraPct + ", intecapPct=" + intecapPct
                + ", defaultMonthlyBonification=" + defaultMonthlyBonification
                + ", isrPersonalDeduction=" + isrPersonalDeduction + ", isrBracketLimit=" + isrBracketLimit
                + ", isrRate1Pct=" + isrRate1Pct + ", isrRate2Pct=" + isrRate2Pct + ", isrFixedBracket2=" + isrFixedBracket2
                + (defaults ? "" : ", overtimeFactor=" + overtimeFactor + ", holidayFactor=" + holidayFactor
                        + ", workdayHours=" + workdayHours)
                + "]";
    }
}
