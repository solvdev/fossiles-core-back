package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeskSlotFinderTest {

    @Test
    void oplZeroHoursGetsTodayEvenIfDesksAreFull() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        Map<LocalDate, Map<Integer, Double>> schedule = new HashMap<>();
        Map<Integer, Double> day = new HashMap<>();
        day.put(1, 4.0);
        day.put(2, 4.0);
        schedule.put(monday, day);

        DeskSlotFinder.Slot slot = DeskSlotFinder.findEarliest(schedule, 2, monday, 0.0);
        assertThat(slot.date()).isEqualTo(monday);
        assertThat(slot.desk()).isEqualTo(1);
    }

    @Test
    void regularHoursSkipFullDay() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Map<LocalDate, Map<Integer, Double>> schedule = new HashMap<>();
        Map<Integer, Double> day = new HashMap<>();
        day.put(1, 4.0);
        schedule.put(monday, day);

        DeskSlotFinder.Slot slot = DeskSlotFinder.findEarliest(schedule, 1, monday, 1.0);
        assertThat(slot.date()).isEqualTo(monday.with(TemporalAdjusters.next(DayOfWeek.TUESDAY)));
        assertThat(slot.desk()).isEqualTo(1);
    }
}
