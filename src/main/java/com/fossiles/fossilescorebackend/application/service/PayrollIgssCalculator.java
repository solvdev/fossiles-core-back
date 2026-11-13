package com.fossiles.fossilescorebackend.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cuotas sobre el salario del período. La bonificación incentivo no es base de IGSS.
 * Separado del cálculo de la línea para sumar a la base otros ingresos afectos, como comisiones.
 */
public final class PayrollIgssCalculator {

    public record Contributions(BigDecimal employee, BigDecimal employer, BigDecimal irtra, BigDecimal intecap) {
        public static final Contributions NONE =
                new Contributions(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private PayrollIgssCalculator() {}

    public static Contributions contributions(BigDecimal salaryBase, PayrollParameters parameters) {
        return new Contributions(
                percent(salaryBase, parameters.igssEmployeePct()),
                percent(salaryBase, parameters.igssEmployerPct()),
                percent(salaryBase, parameters.irtraPct()),
                percent(salaryBase, parameters.intecapPct()));
    }

    static BigDecimal percent(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
