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

    /**
     * {@code igssEnabled} e {@code irtraEnabled} son los interruptores de la ficha: sin IGSS no se descuenta ni se paga
     * IGSS; sin IRTRA no se paga IRTRA ni INTECAP. Vacío cuenta como que sí.
     */
    /**
     * {@code extraPay} es lo que ganó en horas extra y feriados trabajados (ya calculado con el valor de la hora); suma a
     * la base del IGSS. {@code unpaidAbsenceDays} son los días de ausencia sin goce, que restan días pagados.
     */
    public record Input(String employmentType, BigDecimal monthlySalary, BigDecimal monthlyBonification,
                        LocalDate hireDate, LocalDate terminationDate,
                        LocalDate periodStart, LocalDate periodEnd, BigDecimal pendingOpi,
                        Boolean igssEnabled, Boolean irtraEnabled, BigDecimal extraPay, BigDecimal unpaidAbsenceDays) {
        public Input(String employmentType, BigDecimal monthlySalary, BigDecimal monthlyBonification,
                     LocalDate hireDate, LocalDate terminationDate,
                     LocalDate periodStart, LocalDate periodEnd, BigDecimal pendingOpi,
                     Boolean igssEnabled, Boolean irtraEnabled) {
            this(employmentType, monthlySalary, monthlyBonification, hireDate, terminationDate,
                    periodStart, periodEnd, pendingOpi, igssEnabled, irtraEnabled, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public Input(String employmentType, BigDecimal monthlySalary, BigDecimal monthlyBonification,
                     LocalDate hireDate, LocalDate terminationDate,
                     LocalDate periodStart, LocalDate periodEnd, BigDecimal pendingOpi) {
            this(employmentType, monthlySalary, monthlyBonification, hireDate, terminationDate,
                    periodStart, periodEnd, pendingOpi, Boolean.TRUE, Boolean.TRUE);
        }
    }

    public record Result(BigDecimal daysPaid, BigDecimal baseSalary, BigDecimal bonification,
                         BigDecimal igssEmployee, BigDecimal isr, BigDecimal opiDiscount, BigDecimal otherDeductions,
                         BigDecimal netPay, BigDecimal igssEmployer, BigDecimal irtra, BigDecimal intecap,
                         BigDecimal extraPay) {

        public BigDecimal gross() {
            return baseSalary.add(bonification).add(extraPay);
        }

        public BigDecimal deductions() {
            return igssEmployee.add(isr).add(opiDiscount).add(otherDeductions);
        }
    }

    private PayrollCalculator() {}

    public static Result calculate(Input input, PayrollParameters parameters) {
        BigDecimal monthlySalary = orZero(input.monthlySalary());
        BigDecimal daysPaid = daysPaid(input.periodStart(), input.periodEnd(), input.hireDate(), input.terminationDate());
        BigDecimal absence = orZero(input.unpaidAbsenceDays());
        if (absence.signum() > 0) {
            daysPaid = daysPaid.subtract(absence).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal extraPay = orZero(input.extraPay()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = prorate(monthlySalary, daysPaid);
        boolean planilla = !PayrollRules.EMPLOYMENT_SERVICIOS.equals(input.employmentType());

        BigDecimal bonification = BigDecimal.ZERO.setScale(2);
        PayrollIgssCalculator.Contributions igss = PayrollIgssCalculator.Contributions.NONE;
        BigDecimal isr = BigDecimal.ZERO.setScale(2);
        if (planilla && daysPaid.signum() > 0) {
            BigDecimal monthlyBonification = input.monthlyBonification() != null
                    ? input.monthlyBonification() : parameters.defaultMonthlyBonification();
            bonification = prorate(monthlyBonification, daysPaid);
            PayrollIgssCalculator.Contributions all = PayrollIgssCalculator.contributions(base.add(extraPay), parameters);
            BigDecimal none = BigDecimal.ZERO.setScale(2);
            boolean igssOn = !Boolean.FALSE.equals(input.igssEnabled());
            boolean irtraOn = !Boolean.FALSE.equals(input.irtraEnabled());
            igss = new PayrollIgssCalculator.Contributions(
                    igssOn ? all.employee() : none, igssOn ? all.employer() : none,
                    irtraOn ? all.irtra() : none, irtraOn ? all.intecap() : none);
            isr = PayrollIsrCalculator.perPayPeriod(monthlySalary, daysPaid, parameters, igssOn);
        }

        BigDecimal otherDeductions = BigDecimal.ZERO.setScale(2);
        BigDecimal beforeOpi = base.add(bonification).add(extraPay).subtract(igss.employee()).subtract(isr).subtract(otherDeductions);
        if (beforeOpi.signum() < 0) {
            beforeOpi = BigDecimal.ZERO.setScale(2);
        }
        // Lo que no alcanza a descontarse queda como saldo para la siguiente planilla.
        BigDecimal opi = orZero(input.pendingOpi()).setScale(2, RoundingMode.HALF_UP).min(beforeOpi).max(BigDecimal.ZERO);
        BigDecimal net = beforeOpi.subtract(opi);

        return new Result(daysPaid, base, bonification, igss.employee(), isr, opi, otherDeductions, net,
                igss.employer(), igss.irtra(), igss.intecap(), extraPay);
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
