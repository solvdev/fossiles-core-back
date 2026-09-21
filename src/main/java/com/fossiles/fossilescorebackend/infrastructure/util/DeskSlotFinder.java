package com.fossiles.fossilescorebackend.infrastructure.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Primer hueco de mesa usando horas base (cupo 4h). Horas 0 (OPL) entran el primer día hábil
 * aunque las mesas ya estén llenas.
 */
public final class DeskSlotFinder {

    public record Slot(LocalDate date, int desk) {}

    private DeskSlotFinder() {
    }

    public static Slot findEarliest(
            Map<LocalDate, Map<Integer, Double>> scheduleMap,
            int numDesks,
            LocalDate startDate,
            double requiredHours) {
        LocalDate currentDate = startDate;
        int desks = Math.max(numDesks, 1);
        double needed = Math.max(requiredHours, 0.0);

        for (int dayOffset = 0; dayOffset < 365; dayOffset++) {
            DayOfWeek dow = currentDate.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                currentDate = currentDate.plusDays(1);
                continue;
            }

            Map<Integer, Double> daySchedule = scheduleMap.getOrDefault(currentDate, Map.of());
            Integer bestDesk = null;
            double bestLoad = Double.MAX_VALUE;
            for (int desk = 1; desk <= desks; desk++) {
                double usedHours = daySchedule.getOrDefault(desk, 0.0);
                double availableHours = ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY - usedHours;
                if (availableHours + 1e-9 >= needed) {
                    if (usedHours < bestLoad - 1e-9
                            || (Math.abs(usedHours - bestLoad) < 1e-9 && (bestDesk == null || desk < bestDesk))) {
                        bestLoad = usedHours;
                        bestDesk = desk;
                    }
                }
            }
            if (bestDesk != null) {
                return new Slot(currentDate, bestDesk);
            }
            currentDate = currentDate.plusDays(1);
        }

        LocalDate fallback = startDate;
        while (!ProductionPlanningConstants.isWorkday(fallback)) {
            fallback = fallback.plusDays(1);
        }
        return new Slot(fallback, 1);
    }

    public static void addLoad(Map<LocalDate, Map<Integer, Double>> scheduleMap, Slot slot, double hours) {
        if (slot == null || hours <= 0) {
            return;
        }
        scheduleMap
                .computeIfAbsent(slot.date(), d -> new HashMap<>())
                .merge(slot.desk(), hours, Double::sum);
    }

    public static LocalDate nextWorkday(LocalDate from) {
        LocalDate d = from;
        while (!ProductionPlanningConstants.isWorkday(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
