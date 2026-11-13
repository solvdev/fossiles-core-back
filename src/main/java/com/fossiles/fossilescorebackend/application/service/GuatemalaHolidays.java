package com.fossiles.fossilescorebackend.application.service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Feriados y asuetos de Guatemala con su nombre, para que quien registra novedades vea cuáles hay en el año.
 * Son los mismos días que {@link PayrollCalendar} no usa para pagar. El 15 de agosto es asueto solo en la ciudad de
 * Guatemala; el 24 y el 31 de diciembre son asueto desde el mediodía.
 */
public final class GuatemalaHolidays {

    public record Holiday(LocalDate date, String name, boolean halfDay, boolean cityOnly) {}

    private GuatemalaHolidays() {}

    public static List<Holiday> forYear(int year) {
        List<Holiday> holidays = new ArrayList<>();
        holidays.add(fixed(year, 1, 1, "Año Nuevo", false, false));
        LocalDate easter = PayrollCalendar.easterSunday(year);
        holidays.add(new Holiday(easter.minusDays(3), "Jueves Santo", false, false));
        holidays.add(new Holiday(easter.minusDays(2), "Viernes Santo", false, false));
        holidays.add(new Holiday(easter.minusDays(1), "Sábado Santo", false, false));
        holidays.add(fixed(year, 5, 1, "Día del Trabajo", false, false));
        holidays.add(fixed(year, 6, 30, "Día del Ejército", false, false));
        holidays.add(fixed(year, 8, 15, "Virgen de la Asunción (ciudad de Guatemala)", false, true));
        holidays.add(fixed(year, 9, 15, "Día de la Independencia", false, false));
        holidays.add(fixed(year, 10, 20, "Día de la Revolución", false, false));
        holidays.add(fixed(year, 11, 1, "Día de Todos los Santos", false, false));
        holidays.add(fixed(year, 12, 24, "Nochebuena (desde el mediodía)", true, false));
        holidays.add(fixed(year, 12, 25, "Navidad", false, false));
        holidays.add(fixed(year, 12, 31, "Fin de año (desde el mediodía)", true, false));
        holidays.sort(Comparator.comparing(Holiday::date));
        return holidays;
    }

    public static boolean isHoliday(LocalDate date) {
        return forYear(date.getYear()).stream().anyMatch(holiday -> holiday.date().equals(date));
    }

    public static String nameOf(LocalDate date) {
        return forYear(date.getYear()).stream().filter(holiday -> holiday.date().equals(date))
                .map(Holiday::name).findFirst().orElse(null);
    }

    private static Holiday fixed(int year, int month, int day, String name, boolean halfDay, boolean cityOnly) {
        return new Holiday(MonthDay.of(month, day).atYear(year), name, halfDay, cityOnly);
    }
}
