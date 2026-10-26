package com.fossiles.fossilescorebackend.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ISR de asalariados: renta anual proyectada (salario mensual × 12) menos la deducción personal y el IGSS laboral.
 * Aguinaldo, bono 14 y bonificación incentivo están exentos y no entran. La retención de cada quincena es 1/24 del
 * impuesto anual, proporcional a los días pagados.
 */
public final class PayrollIsrCalculator {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal PAY_PERIODS_PER_YEAR = BigDecimal.valueOf(24);
    private static final BigDecimal FULL_PERIOD_DAYS = BigDecimal.valueOf(15);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PayrollIsrCalculator() {}

    public static BigDecimal annualTaxableIncome(BigDecimal monthlySalary, PayrollParameters parameters) {
        BigDecimal annualSalary = monthlySalary.multiply(TWELVE);
        BigDecimal annualIgss = annualSalary.multiply(parameters.igssEmployeePct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal taxable = annualSalary.subtract(parameters.isrPersonalDeduction()).subtract(annualIgss);
        return taxable.signum() > 0 ? taxable : BigDecimal.ZERO;
    }

    public static BigDecimal annualTax(BigDecimal taxableIncome, PayrollParameters parameters) {
        if (taxableIncome.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal limit = parameters.isrBracketLimit();
        if (taxableIncome.compareTo(limit) <= 0) {
            return taxableIncome.multiply(parameters.isrRate1Pct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        BigDecimal excess = taxableIncome.subtract(limit);
        return parameters.isrFixedBracket2()
                .add(excess.multiply(parameters.isrRate2Pct()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
    }

    public static BigDecimal perPayPeriod(BigDecimal monthlySalary, BigDecimal daysPaid, PayrollParameters parameters) {
        BigDecimal annual = annualTax(annualTaxableIncome(monthlySalary, parameters), parameters);
        if (annual.signum() == 0 || daysPaid.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return annual.multiply(daysPaid)
                .divide(PAY_PERIODS_PER_YEAR.multiply(FULL_PERIOD_DAYS), 2, RoundingMode.HALF_UP);
    }
}
