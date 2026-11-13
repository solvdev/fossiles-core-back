package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Cálculo de una línea de planilla quincenal. La quincena completa son 15 días de un mes comercial de 30;
 * un ingreso o una baja dentro del período paga la parte proporcional de los días calendario trabajados.
 * SERVICIOS no lleva bonificación, IGSS, ISR ni cuotas patronales: solo admite el descuento de compras de colaborador.
 */
public final class PayrollCalculator {

    private static final BigDecimal FULL_PERIOD_DAYS = BigDecimal.valueOf(15);
    private static final BigDecimal COMMERCIAL_MONTH_DAYS = BigDecimal.valueOf(30);

    public record Input(String employmentType, BigDecimal monthlySalary, BigDecimal monthlyBonification,
                        LocalDate hireDate, LocalDate terminationDate,
                        LocalDate periodStart, LocalDate periodEnd, BigDecimal pendingOpi) {}

    public record Result(BigDecimal daysPaid, BigDecimal baseSalary, BigDecimal bonification,
                         BigDecimal igssEmployee, BigDecimal isr, BigDecimal opiDiscount, BigDecimal otherDeductions,
                         BigDecimal netPay, BigDecimal igssEmployer, BigDecimal irtra, BigDecimal intecap) {

        public BigDecimal gross() {
            return baseSalary.add(bonification);
        }

        public BigDecimal deductions() {
            return igssEmployee.add(isr).add(opiDiscount).add(otherDeductions);
        }
    }

    private PayrollCalculator() {}

    public static Result calculate(Input input, PayrollParameters parameters) {
        BigDecimal monthlySalary = orZero(input.monthlySalary());
        BigDecimal daysPaid = daysPaid(input.periodStart(), input.periodEnd(), input.hireDate(), input.terminationDate());
        BigDecimal base = prorate(monthlySalary, daysPaid);
        boolean planilla = !PayrollRules.EMPLOYMENT_SERVICIOS.equals(input.employmentType());

        BigDecimal bonification = BigDecimal.ZERO.setScale(2);
        PayrollIgssCalculator.Contributions igss = PayrollIgssCalculator.Contributions.NONE;
        BigDecimal isr = BigDecimal.ZERO.setScale(2);
        if (planilla && daysPaid.signum() > 0) {
            BigDecimal monthlyBonification = input.monthlyBonification() != null
                    ? input.monthlyBonification() : parameters.defaultMonthlyBonification();
            bonification = prorate(monthlyBonification, daysPaid);
            igss = PayrollIgssCalculator.contributions(base, parameters);
            isr = PayrollIsrCalculator.perPayPeriod(monthlySalary, daysPaid, parameters);
        }

        BigDecimal otherDeductions = BigDecimal.ZERO.setScale(2);
        BigDecimal beforeOpi = base.add(bonification).subtract(igss.employee()).subtract(isr).subtract(otherDeductions);
        if (beforeOpi.signum() < 0) {
            beforeOpi = BigDecimal.ZERO.setScale(2);
        }
        // Lo que no alcanza a descontarse queda como saldo para la siguiente planilla.
        BigDecimal opi = orZero(input.pendingOpi()).setScale(2, RoundingMode.HALF_UP).min(beforeOpi).max(BigDecimal.ZERO);
        BigDecimal net = beforeOpi.subtract(opi);

        return new Result(daysPaid, base, bonification, igss.employee(), isr, opi, otherDeductions, net,
                igss.employer(), igss.irtra(), igss.intecap());
    }

    /** 15 si trabajó todo el período; si entró o salió dentro de él, la proporción de días calendario trabajados. */
    public static BigDecimal daysPaid(LocalDate periodStart, LocalDate periodEnd, LocalDate hireDate, LocalDate terminationDate) {
        LocalDate start = hireDate != null && hireDate.isAfter(periodStart) ? hireDate : periodStart;
        LocalDate end = terminationDate != null && terminationDate.isBefore(periodEnd) ? terminationDate : periodEnd;
        if (end.isBefore(start)) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (start.equals(periodStart) && end.equals(periodEnd)) {
            return FULL_PERIOD_DAYS.setScale(2);
        }
        long worked = ChronoUnit.DAYS.between(start, end) + 1;
        long periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        return FULL_PERIOD_DAYS.multiply(BigDecimal.valueOf(worked))
                .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal prorate(BigDecimal monthly, BigDecimal days) {
        return orZero(monthly).multiply(days).divide(COMMERCIAL_MONTH_DAYS, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
