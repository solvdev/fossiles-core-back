package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    @Test
    void pickDeskOnDayChoosesLeastLoadedWithRoom() {
        Map<Integer, Double> loads = new HashMap<>();
        loads.put(1, 3.0);
        loads.put(2, 1.0);
        loads.put(3, 2.0);

        assertThat(DeskSlotFinder.pickDeskOnDay(loads, 3, 1.5)).isEqualTo(2);
    }

    @Test
    void pickDeskOnDayReturnsNullWhenDayIsFull() {
        Map<Integer, Double> loads = new HashMap<>();
        loads.put(1, 3.5);
        loads.put(2, 3.0);

        assertThat(DeskSlotFinder.pickDeskOnDay(loads, 2, 1.5)).isNull();
    }

    @Test
    void pickDeskOnDayBreaksTiesAcrossAllEmptyDesks() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(DeskSlotFinder.pickDeskOnDay(new HashMap<>(), 3, 1.0));
        }
        assertThat(seen).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void pickDeskOnDayOversizedTaskOnlyFitsEmptyDesk() {
        Map<Integer, Double> loads = new HashMap<>();
        loads.put(1, 0.5);

        assertThat(DeskSlotFinder.pickDeskOnDay(loads, 2, 4.5)).isEqualTo(2);
        loads.put(2, 0.5);
        assertThat(DeskSlotFinder.pickDeskOnDay(loads, 2, 4.5)).isNull();
    }
}
