package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GuatemalaHolidaysTest {

    @Test
    void listsTheLegalHolidaysOfTheYearInOrderWithSemanaSantaFromEaster() {
        var holidays = GuatemalaHolidays.forYear(2026);
        assertThat(holidays).hasSize(13);
        assertThat(holidays).isSortedAccordingTo(java.util.Comparator.comparing(GuatemalaHolidays.Holiday::date));
        // Pascua 2026 fue el 5 de abril.
        assertThat(GuatemalaHolidays.nameOf(LocalDate.of(2026, 4, 2))).isEqualTo("Jueves Santo");
        assertThat(GuatemalaHolidays.nameOf(LocalDate.of(2026, 4, 3))).isEqualTo("Viernes Santo");
        assertThat(GuatemalaHolidays.nameOf(LocalDate.of(2026, 9, 15))).isEqualTo("Día de la Independencia");
        assertThat(GuatemalaHolidays.nameOf(LocalDate.of(2026, 10, 20))).isEqualTo("Día de la Revolución");
    }

    @Test
    void marksTheHalfDaysAndTheCityOnlyFeast() {
        var holidays = GuatemalaHolidays.forYear(2026);
        assertThat(holidays).filteredOn(GuatemalaHolidays.Holiday::halfDay).extracting(GuatemalaHolidays.Holiday::date)
                .containsExactly(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 31));
        assertThat(holidays).filteredOn(GuatemalaHolidays.Holiday::cityOnly).extracting(GuatemalaHolidays.Holiday::date)
                .containsExactly(LocalDate.of(2026, 8, 15));
    }

    @Test
    void anOrdinaryDayIsNotAHoliday() {
        assertThat(GuatemalaHolidays.isHoliday(LocalDate.of(2026, 10, 7))).isFalse();
        assertThat(GuatemalaHolidays.nameOf(LocalDate.of(2026, 10, 7))).isNull();
    }
}
