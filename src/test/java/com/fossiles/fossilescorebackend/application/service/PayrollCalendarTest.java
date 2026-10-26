package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollCalendarTest {

    @Test
    void plantPaysOnFifthAndTwentiethForTheQuincenaBefore() {
        assertThat(PayrollCalendar.scheduledDates("PLANTA", YearMonth.of(2026, 11)))
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 20));
        var twentieth = PayrollCalendar.payDay("PLANTA", LocalDate.of(2026, 11, 20));
        assertThat(twentieth.periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(twentieth.periodEnd()).isEqualTo(LocalDate.of(2026, 11, 15));
        var fifth = PayrollCalendar.payDay("PLANTA", LocalDate.of(2027, 1, 5));
        assertThat(fifth.periodStart()).isEqualTo(LocalDate.of(2026, 12, 16));
        assertThat(fifth.periodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(fifth.payDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void administrationPaysOnFifteenthAndLastDayOfMonth() {
        assertThat(PayrollCalendar.scheduledDates("ADMINISTRACION", YearMonth.of(2026, 2)))
                .containsExactly(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 28));
        var endOfMonth = PayrollCalendar.payDay("ADMINISTRACION", LocalDate.of(2026, 10, 31));
        assertThat(endOfMonth.periodStart()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(endOfMonth.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void weekendsAndHolidaysMoveToThePreviousBusinessDay() {
        // 20 de octubre (feriado, martes) pasa al lunes 19.
        assertThat(PayrollCalendar.payDay("PLANTA", LocalDate.of(2026, 10, 20)).payDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        // 15 de septiembre (feriado, martes) pasa al lunes 14.
        assertThat(PayrollCalendar.payDay("ADMINISTRACION", LocalDate.of(2026, 9, 15)).payDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        // Sábado 5 de diciembre pasa al viernes 4.
        assertThat(PayrollCalendar.payDay("PLANTA", LocalDate.of(2026, 12, 5)).payDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        // Domingo 15 de noviembre pasa al viernes 13.
        assertThat(PayrollCalendar.payDay("ADMINISTRACION", LocalDate.of(2026, 11, 15)).payDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        // Domingo de Pascua 5 de abril: sábado, viernes y jueves santos no son hábiles, se paga el miércoles 1.
        assertThat(PayrollCalendar.payDay("PLANTA", LocalDate.of(2026, 4, 5)).payDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        // 31 de diciembre (asueto desde el mediodía, jueves) pasa al miércoles 30, como el cheque de muestra de 2025.
        assertThat(PayrollCalendar.payDay("ADMINISTRACION", LocalDate.of(2026, 12, 31)).payDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(PayrollCalendar.payDay("ADMINISTRACION", LocalDate.of(2025, 12, 31)).payDate()).isEqualTo(LocalDate.of(2025, 12, 30));
        assertThat(PayrollCalendar.isBusinessDay(LocalDate.of(2026, 12, 24))).isFalse();
    }

    @Test
    void easterFollowsTheGregorianComputus() {
        assertThat(PayrollCalendar.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(PayrollCalendar.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(PayrollCalendar.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    }

    @Test
    void upcomingListsTheNextScheduledDatesAndRejectsOffScheduleDates() {
        assertThat(PayrollCalendar.upcoming("PLANTA", LocalDate.of(2026, 10, 19), 3))
                .extracting(PayrollCalendar.PayDay::scheduledDate)
                .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 20));
        assertThat(PayrollCalendar.isScheduledDate("PLANTA", LocalDate.of(2026, 10, 15))).isFalse();
        assertThatThrownBy(() -> PayrollCalendar.payDay("PLANTA", LocalDate.of(2026, 10, 15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
