package com.fossiles.fossilescorebackend.application.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Une cada visita del calendario con su conteo físico. La supervisora no siempre va el día programado, así que el
 * conteo puede caer hasta una semana antes o después; cada conteo se usa para una sola visita (la más cercana).
 */
final class KioskCountVisitMatcher {

    /** Días de diferencia entre la fecha programada y el inventario del conteo para considerarlos la misma visita. */
    static final int MATCH_DAYS = 7;

    private KioskCountVisitMatcher() {
    }

    /** {@code status} null = conteo del historial del Excel (hecho, sin estado en el sistema). */
    record Count(Long id, LocalDate periodTo, String status) {
        boolean draft() {
            return "DRAFT".equals(status);
        }
    }

    record Visit(long id, long locationId, LocalDate date, boolean revisit, int daySeq) {
    }

    private record Pair(List<Visit> visits, Count count, long distance) {
    }

    /** Devuelve, por id de visita, el conteo que le corresponde. */
    static Map<Long, Count> match(List<Visit> visits, Map<Long, List<Count>> countsByLocation) {
        // Una visita de varios días (Entrecueros, Celajes) es una sola: comparte el conteo.
        List<List<Visit>> units = new ArrayList<>();
        Map<Long, List<Visit>> lastByLocation = new HashMap<>();
        visits.stream().sorted(Comparator.comparing(Visit::date)).forEach(v -> {
            List<Visit> last = lastByLocation.get(v.locationId());
            if (v.daySeq() > 1 && last != null
                    && ChronoUnit.DAYS.between(last.get(last.size() - 1).date(), v.date()) <= 3) {
                last.add(v);
                return;
            }
            List<Visit> unit = new ArrayList<>(List.of(v));
            units.add(unit);
            lastByLocation.put(v.locationId(), unit);
        });

        List<Pair> pairs = new ArrayList<>();
        for (List<Visit> unit : units) {
            Visit first = unit.get(0);
            LocalDate end = unit.get(unit.size() - 1).date();
            // Volver a ir es un conteo nuevo: no puede tomar el que se hizo antes.
            LocalDate from = first.date().minusDays(first.revisit() ? 1 : MATCH_DAYS);
            LocalDate to = end.plusDays(MATCH_DAYS);
            for (Count c : countsByLocation.getOrDefault(first.locationId(), List.of())) {
                if (!c.periodTo().isBefore(from) && !c.periodTo().isAfter(to)) {
                    pairs.add(new Pair(unit, c, Math.abs(ChronoUnit.DAYS.between(first.date(), c.periodTo()))));
                }
            }
        }
        pairs.sort(Comparator.comparing((Pair p) -> p.count().draft()).thenComparingLong(Pair::distance));

        Map<Long, Count> result = new HashMap<>();
        Set<Count> used = new HashSet<>();
        for (Pair p : pairs) {
            if (result.containsKey(p.visits().get(0).id()) || used.contains(p.count())) {
                continue;
            }
            used.add(p.count());
            p.visits().forEach(v -> result.put(v.id(), p.count()));
        }
        return result;
    }
}
