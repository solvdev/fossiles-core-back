package com.fossiles.fossilescorebackend.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pone los conteos pendientes de liquidar en los días de una semana: un kiosco por día (dos días seguidos para los
 * kioscos de visita doble, como Entrecueros y Celajes), del conteo más viejo al más reciente. No liquida un conteo
 * antes de {@code minLeadDays} día(s) después de hecho: el conteo se manda el día siguiente y ahí empieza la revisión.
 */
public final class KioskLiquidationPlanner {

    /** Al día siguiente del conteo mandan el reporte y contabilidad empieza a revisar: no se liquida el mismo día. */
    static final int MIN_LEAD_DAYS = 1;

    private KioskLiquidationPlanner() {
    }

    public record Pending(long locationId, LocalDate periodTo, int days) {
    }

    public record Slot(long locationId, LocalDate periodTo, LocalDate date, int days) {
    }

    /**
     * @param weekDays      días hábiles de la semana donde se puede liquidar, en orden
     * @param occupiedDays  días que ya tienen una liquidación de esta supervisora
     * @param pendingOldest conteos por liquidar, del más viejo al más reciente
     */
    public static List<Slot> assign(List<LocalDate> weekDays, Set<LocalDate> occupiedDays, List<Pending> pendingOldest,
                                    int minLeadDays) {
        Set<LocalDate> used = new HashSet<>(occupiedDays);
        Set<Long> placed = new HashSet<>();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < weekDays.size(); i++) {
            LocalDate day = weekDays.get(i);
            if (used.contains(day)) {
                continue;
            }
            for (Pending p : pendingOldest) {
                if (placed.contains(p.locationId()) || day.isBefore(p.periodTo().plusDays(minLeadDays))) {
                    continue;
                }
                int days = Math.max(1, p.days());
                if (i + days > weekDays.size()) {
                    continue;
                }
                boolean free = true;
                for (int d = 0; d < days; d++) {
                    if (used.contains(weekDays.get(i + d))) {
                        free = false;
                    }
                }
                if (!free) {
                    continue;
                }
                for (int d = 0; d < days; d++) {
                    used.add(weekDays.get(i + d));
                }
                placed.add(p.locationId());
                slots.add(new Slot(p.locationId(), p.periodTo(), day, days));
                break;
            }
        }
        return slots;
    }
}
