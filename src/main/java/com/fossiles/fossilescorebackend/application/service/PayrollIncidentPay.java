package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollIncidentEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Lo que las novedades de una persona suman o restan en un período de planilla.
 * <ul>
 *   <li>Horas extra y feriado trabajado se pagan si su día cae en el período: horas × valor de la hora × recargo
 *       (o el pago por hora que se anotó en la novedad). El valor de la hora es el sueldo mensual entre 30 y entre las
 *       horas de la jornada.</li>
 *   <li>Ausencia sin goce: cada día calendario dentro del período resta un día pagado.</li>
 *   <li>Vacaciones: se cuentan para informar, no cambian el pago.</li>
 * </ul>
 */
public final class PayrollIncidentPay {

    private static final BigDecimal COMMERCIAL_MONTH_DAYS = BigDecimal.valueOf(30);

    public record Summary(BigDecimal extraPay, BigDecimal absenceDays, BigDecimal vacationDays) {
        public static final Summary EMPTY = new Summary(
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        public boolean isEmpty() {
            return extraPay.signum() == 0 && absenceDays.signum() == 0 && vacationDays.signum() == 0;
        }
    }

    private PayrollIncidentPay() {}

    /** Sueldo mensual entre 30 días y entre las horas de la jornada. */
    public static BigDecimal hourlyRate(BigDecimal monthlySalary, PayrollParameters parameters) {
        BigDecimal salary = monthlySalary == null ? BigDecimal.ZERO : monthlySalary;
        BigDecimal workday = parameters.workdayHours().signum() > 0 ? parameters.workdayHours() : BigDecimal.valueOf(8);
        return salary.divide(COMMERCIAL_MONTH_DAYS, 6, RoundingMode.HALF_UP).divide(workday, 6, RoundingMode.HALF_UP);
    }

    public static Summary summarize(BigDecimal monthlySalary, List<PayrollIncidentEntity> incidents,
                                    LocalDate periodStart, LocalDate periodEnd, PayrollParameters parameters) {
        if (incidents == null || incidents.isEmpty()) {
            return Summary.EMPTY;
        }
        BigDecimal hourly = hourlyRate(monthlySalary, parameters);
        BigDecimal extra = BigDecimal.ZERO;
        long absence = 0;
        long vacation = 0;
        for (PayrollIncidentEntity incident : incidents) {
            if (!PayrollIncidentEntity.STATUS_ACTIVE.equals(incident.getStatus())) {
                continue;
            }
            switch (incident.getKind()) {
                case PayrollIncidentEntity.KIND_OVERTIME -> {
                    if (within(incident.getDateFrom(), periodStart, periodEnd)) {
                        extra = extra.add(hoursPay(incident, hours(incident, null), hourly, parameters.overtimeFactor()));
                    }
                }
                case PayrollIncidentEntity.KIND_HOLIDAY_WORKED -> {
                    if (within(incident.getDateFrom(), periodStart, periodEnd)) {
                        extra = extra.add(hoursPay(incident, hours(incident, parameters.workdayHours()), hourly, parameters.holidayFactor()));
                    }
                }
                case PayrollIncidentEntity.KIND_ABSENCE -> absence += overlapDays(incident, periodStart, periodEnd);
                case PayrollIncidentEntity.KIND_VACATION -> vacation += overlapDays(incident, periodStart, periodEnd);
                default -> { }
            }
        }
        return new Summary(extra.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(absence).setScale(2), BigDecimal.valueOf(vacation).setScale(2));
    }

    private static BigDecimal hoursPay(PayrollIncidentEntity incident, BigDecimal hours, BigDecimal hourly, BigDecimal factor) {
        BigDecimal rate = incident.getHourlyRateOverride() != null ? incident.getHourlyRateOverride() : hourly.multiply(factor);
        return hours.multiply(rate);
    }

    private static BigDecimal hours(PayrollIncidentEntity incident, BigDecimal fallback) {
        if (incident.getHours() != null) {
            return incident.getHours();
        }
        return fallback == null ? BigDecimal.ZERO : fallback;
    }

    private static boolean within(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private static long overlapDays(PayrollIncidentEntity incident, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate from = incident.getDateFrom().isAfter(periodStart) ? incident.getDateFrom() : periodStart;
        LocalDate to = incident.getDateTo().isBefore(periodEnd) ? incident.getDateTo() : periodEnd;
        return to.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, to) + 1;
    }
}
