package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fechas de pago por grupo. PLANTA cobra el 5 (segunda quincena del mes anterior) y el 20 (primera quincena);
 * ADMINISTRACION, el 15 (primera quincena) y el último día del mes (segunda quincena).
 * Si la fecha cae en fin de semana o feriado se paga el día hábil anterior.
 */
public final class PayrollCalendar {

    /**
     * Asuetos del Código de Trabajo (art. 127); el 15 de agosto es la feria de la ciudad de Guatemala.
     * El 24 y el 31 de diciembre son asueto desde el mediodía y tampoco se usan para pagar.
     */
    private static final List<MonthDay> FIXED_HOLIDAYS = List.of(
            MonthDay.of(1, 1), MonthDay.of(5, 1), MonthDay.of(6, 30), MonthDay.of(8, 15),
            MonthDay.of(9, 15), MonthDay.of(10, 20), MonthDay.of(11, 1),
            MonthDay.of(12, 24), MonthDay.of(12, 25), MonthDay.of(12, 31));

    public record PayDay(String payGroup, LocalDate scheduledDate, LocalDate payDate,
                         LocalDate periodStart, LocalDate periodEnd) {}

    private PayrollCalendar() {}

    public static List<LocalDate> scheduledDates(String payGroup, YearMonth month) {
        if (PayrollRules.GROUP_PLANTA.equals(payGroup)) {
            return List.of(month.atDay(5), month.atDay(20));
        }
        if (PayrollRules.GROUP_ADMINISTRACION.equals(payGroup)) {
            return List.of(month.atDay(15), month.atEndOfMonth());
        }
        throw new IllegalArgumentException("Grupo de pago inválido: " + payGroup);
    }

    public static boolean isScheduledDate(String payGroup, LocalDate date) {
        return date != null && scheduledDates(payGroup, YearMonth.from(date)).contains(date);
    }

    public static PayDay payDay(String payGroup, LocalDate scheduledDate) {
        if (!isScheduledDate(payGroup, scheduledDate)) {
            throw new IllegalArgumentException("La fecha " + scheduledDate + " no es día de pago del grupo " + payGroup + ".");
        }
        YearMonth month = YearMonth.from(scheduledDate);
        LocalDate start;
        LocalDate end;
        if (PayrollRules.GROUP_PLANTA.equals(payGroup)) {
            if (scheduledDate.getDayOfMonth() == 5) {
                YearMonth previous = month.minusMonths(1);
                start = previous.atDay(16);
                end = previous.atEndOfMonth();
            } else {
                start = month.atDay(1);
                end = month.atDay(15);
            }
        } else if (scheduledDate.getDayOfMonth() == 15) {
            start = month.atDay(1);
            end = month.atDay(15);
        } else {
            start = month.atDay(16);
            end = month.atEndOfMonth();
        }
        return new PayDay(payGroup, scheduledDate, previousBusinessDay(scheduledDate), start, end);
    }

    /** Próximos días de pago del grupo cuya fecha programada es igual o posterior a {@code from}. */
    public static List<PayDay> upcoming(String payGroup, LocalDate from, int count) {
        List<PayDay> result = new ArrayList<>();
        YearMonth month = YearMonth.from(from);
        while (result.size() < count) {
            for (LocalDate date : scheduledDates(payGroup, month)) {
                if (!date.isBefore(from) && result.size() < count) {
                    result.add(payDay(payGroup, date));
                }
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    public static LocalDate previousBusinessDay(LocalDate date) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays(date.getYear()).contains(date);
    }

    public static Set<LocalDate> holidays(int year) {
        Set<LocalDate> result = new HashSet<>();
        for (MonthDay day : FIXED_HOLIDAYS) {
            result.add(day.atYear(year));
        }
        LocalDate easter = easterSunday(year);
        result.add(easter.minusDays(3));
        result.add(easter.minusDays(2));
        result.add(easter.minusDays(1));
        return result;
    }

    /** Domingo de Pascua (algoritmo gregoriano anónimo). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
