package com.fossiles.fossilescorebackend.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Reparte las visitas de conteo físico de UNA supervisora en un mes.
 * <p>
 * Reglas:
 * <ul>
 *   <li>Una visita por día (solo en los días libres que recibe).</li>
 *   <li>Cada kiosco recibe {@code visitsPerMonth} visitas (1; Miraflores II recibe 2, separadas ~15 días).</li>
 *   <li>Cada visita dura {@code visitDays} días seguidos (Entrecueros y Celajes: 2).</li>
 *   <li>Un kiosco puede tener días de la semana permitidos (Naranjo: lunes a miércoles).</li>
 *   <li>Los kioscos de una misma región van en días seguidos: no se alterna capital / departamento / capital.</li>
 *   <li>Se toma como referencia el fin del último conteo físico de cada kiosco (y por lo tanto el de sus
 *       vecinos de región): conviene que pasen entre {@value #MIN_GAP} y {@value #MAX_GAP} días, y se evita
 *       caer en el mismo día del mes que la vez anterior.</li>
 *   <li>El orden de las regiones, el orden dentro de cada región y los huecos entre viajes son aleatorios.</li>
 * </ul>
 * Se generan muchas combinaciones al azar y se queda la que menos reglas "blandas" rompe. Es una clase pura,
 * sin acceso a base de datos, para poder probarla con una semilla fija.
 */
public final class KioskCountVisitPlanner {

    static final int MIN_GAP = 20;
    static final int MAX_GAP = 40;
    static final int MONTH_DAYS = 30;

    private KioskCountVisitPlanner() {
    }

    /**
     * @param keptDates   días de visita ya existentes este mes que no se mueven (hechas, manuales o pasadas)
     * @param allowedDays días de la semana permitidos; vacío = cualquier día hábil
     */
    public record Kiosk(long locationId, String code, KioskCountRegion region, LocalDate lastCount,
                        int visitsPerMonth, int visitDays, Set<DayOfWeek> allowedDays,
                        List<LocalDate> keptDates) {

        int days() {
            return Math.max(1, visitDays);
        }

        /** Visitas que faltan por programar (cada una de {@link #days()} días seguidos). */
        int eventsNeeded() {
            int kept = keptDates == null ? 0 : keptDates.size();
            int done = (int) Math.ceil(kept / (double) days());
            return Math.max(0, Math.max(1, visitsPerMonth) - done);
        }

        boolean restricted() {
            return allowedDays != null && !allowedDays.isEmpty() && allowedDays.size() < 5;
        }

        boolean allows(LocalDate d) {
            return allowedDays == null || allowedDays.isEmpty() || allowedDays.contains(d.getDayOfWeek());
        }
    }

    public record Visit(long locationId, LocalDate date) {
    }

    public record Plan(List<Visit> visits, List<Long> unscheduledLocationIds, double score) {
    }

    private record Candidate(Map<Long, List<LocalDate>> dates, List<Long> unscheduled, double score) {
    }

    /**
     * @param freeDays días en que la supervisora puede visitar (hábiles, sin feriados ni visitas ya fijas)
     */
    public static Plan plan(List<Kiosk> kiosks, List<LocalDate> freeDays, int iterations, Random random) {
        List<LocalDate> free = freeDays.stream().distinct().sorted().toList();
        Candidate best = null;
        for (int i = 0; i < Math.max(1, iterations); i++) {
            Candidate candidate = build(kiosks, free, random);
            if (best == null || candidate.score < best.score) {
                best = candidate;
            }
        }
        List<Visit> visits = new ArrayList<>();
        best.dates.forEach((locationId, dates) -> dates.forEach(d -> visits.add(new Visit(locationId, d))));
        visits.sort(Comparator.comparing(Visit::date));
        return new Plan(visits, best.unscheduled, best.score);
    }

    private static Candidate build(List<Kiosk> kiosks, List<LocalDate> free, Random rnd) {
        Map<Long, List<LocalDate>> assigned = new LinkedHashMap<>();
        List<Long> unscheduled = new ArrayList<>();
        Set<LocalDate> reserved = new HashSet<>();
        double penalty = 0;

        // 1) Kioscos con reglas fijas (varias visitas al mes o días de semana limitados): se ubican primero.
        List<Kiosk> regular = new ArrayList<>();
        for (Kiosk k : kiosks) {
            if (k.eventsNeeded() <= 0) {
                continue;
            }
            if (k.visitsPerMonth() <= 1 && !k.restricted()) {
                regular.add(k);
                continue;
            }
            int interval = Math.max(7, MONTH_DAYS / Math.max(1, k.visitsPerMonth()));
            List<LocalDate> starts = new ArrayList<>(k.keptDates() == null ? List.of() : k.keptDates());
            List<LocalDate> mine = new ArrayList<>();
            List<LocalDate> newStarts = new ArrayList<>();
            for (int n = k.eventsNeeded(); n > 0; n--) {
                List<LocalDate> run = pickEvent(free, reserved, starts, k, interval, rnd);
                if (run == null) {
                    unscheduled.add(k.locationId());
                    break;
                }
                reserved.addAll(run);
                starts.add(run.get(0));
                newStarts.add(run.get(0));
                mine.addAll(run);
            }
            if (!mine.isEmpty()) {
                assigned.put(k.locationId(), mine);
                if (k.visitsPerMonth() > 1) {
                    penalty += spacingPenalty(starts, interval);
                    penalty += gapPenalty(k.lastCount(), newStarts.stream().min(LocalDate::compareTo).get(),
                            interval - 3, interval + 3);
                } else {
                    penalty += gapPenalty(k.lastCount(), newStarts.get(0), MIN_GAP, MAX_GAP);
                }
            }
        }

        // 2) El resto, por regiones en días seguidos.
        List<LocalDate> days = free.stream().filter(d -> !reserved.contains(d)).toList();
        Map<KioskCountRegion, List<Kiosk>> byRegion = new EnumMap<>(KioskCountRegion.class);
        regular.forEach(k -> byRegion.computeIfAbsent(k.region(), r -> new ArrayList<>()).add(k));

        List<List<Kiosk>> blocks = new ArrayList<>();
        for (List<Kiosk> group : byRegion.values()) {
            List<Kiosk> ordered = orderByDueness(group, rnd);
            if (ordered.size() > 6 && rnd.nextBoolean()) {
                int cut = 3 + rnd.nextInt(ordered.size() - 5);
                blocks.add(new ArrayList<>(ordered.subList(0, cut)));
                blocks.add(new ArrayList<>(ordered.subList(cut, ordered.size())));
            } else {
                blocks.add(ordered);
            }
        }
        Collections.shuffle(blocks, rnd);

        int total = blocks.stream().flatMap(List::stream).mapToInt(Kiosk::days).sum();
        int slack = Math.max(0, days.size() - total);
        int[] gaps = splitSlack(slack, blocks.size() + 1, rnd);

        int pos = 0;
        for (int b = 0; b < blocks.size(); b++) {
            pos += gaps[b];
            List<Kiosk> block = blocks.get(b);
            List<LocalDate> blockDates = new ArrayList<>();
            for (Kiosk k : block) {
                // Una visita de varios días no se parte: si quedaría separada por días reservados, se salta ese día.
                while (pos + k.days() <= days.size() && brokenRun(days.subList(pos, pos + k.days()))) {
                    pos++;
                }
                if (pos + k.days() > days.size()) {
                    unscheduled.add(k.locationId());
                    continue;
                }
                List<LocalDate> run = new ArrayList<>(days.subList(pos, pos + k.days()));
                pos += k.days();
                assigned.computeIfAbsent(k.locationId(), id -> new ArrayList<>()).addAll(run);
                blockDates.addAll(run);
                penalty += gapPenalty(k.lastCount(), run.get(0), MIN_GAP, MAX_GAP);
                penalty += brokenRunPenalty(run);
            }
            if (!block.isEmpty()) {
                penalty += splitPenalty(block.get(0).region(), blockDates, reserved);
            }
        }
        penalty += weeklyLoadPenalty(assigned, free);
        penalty += rnd.nextDouble() * 0.05;
        return new Candidate(assigned, unscheduled, penalty);
    }

    /** Región seguida: del conteo más viejo al más reciente, con ruido para que el orden varíe cada mes. */
    private static List<Kiosk> orderByDueness(List<Kiosk> group, Random rnd) {
        Map<Kiosk, Long> keys = new LinkedHashMap<>();
        for (Kiosk k : group) {
            long base = k.lastCount() == null ? Long.MIN_VALUE / 2 : k.lastCount().toEpochDay();
            keys.put(k, base + rnd.nextInt(13) - 6);
        }
        List<Kiosk> ordered = new ArrayList<>(group);
        ordered.sort(Comparator.comparingLong(keys::get));
        if (rnd.nextInt(4) == 0) {
            Collections.reverse(ordered);
        }
        return ordered;
    }

    /** Reparte los días sobrantes al azar entre los huecos antes/entre/después de los viajes. */
    private static int[] splitSlack(int slack, int parts, Random rnd) {
        int[] gaps = new int[parts];
        double[] weights = new double[parts];
        double sum = 0;
        for (int i = 0; i < parts; i++) {
            weights[i] = rnd.nextDouble();
            sum += weights[i];
        }
        int used = 0;
        for (int i = 0; i < parts - 1; i++) {
            gaps[i] = (int) Math.floor(slack * weights[i] / sum);
            used += gaps[i];
        }
        gaps[parts - 1] = slack - used;
        return gaps;
    }

    /**
     * Una visita completa del kiosco (sus {@code days()} días seguidos) en días libres que él permite y que
     * quedan a ~{@code interval} días de sus otras visitas del mes. Devuelve null si no cabe.
     */
    private static List<LocalDate> pickEvent(List<LocalDate> free, Set<LocalDate> reserved, List<LocalDate> starts,
                                             Kiosk kiosk, int interval, Random rnd) {
        List<List<LocalDate>> runs = new ArrayList<>();
        for (int i = 0; i < free.size(); i++) {
            if (i + kiosk.days() > free.size()) {
                break;
            }
            List<LocalDate> run = free.subList(i, i + kiosk.days());
            boolean ok = true;
            for (int j = 0; j < run.size(); j++) {
                LocalDate d = run.get(j);
                if (reserved.contains(d) || !kiosk.allows(d)
                        || (j > 0 && ChronoUnit.DAYS.between(run.get(j - 1), d) > 3)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                runs.add(new ArrayList<>(run));
            }
        }
        if (runs.isEmpty()) {
            return null;
        }
        if (starts.isEmpty()) {
            int limit = kiosk.visitsPerMonth() > 1 ? Math.max(1, (int) Math.ceil(runs.size() * 0.55)) : runs.size();
            return runs.get(rnd.nextInt(limit));
        }
        List<List<LocalDate>> fit = runs.stream()
                .filter(r -> starts.stream().anyMatch(a ->
                        Math.abs(Math.abs(ChronoUnit.DAYS.between(a, r.get(0))) - interval) <= 2))
                .toList();
        if (!fit.isEmpty()) {
            return fit.get(rnd.nextInt(fit.size()));
        }
        LocalDate ref = starts.get(starts.size() - 1);
        return runs.stream()
                .min(Comparator.comparingLong(r -> Math.abs(Math.abs(ChronoUnit.DAYS.between(ref, r.get(0))) - interval)))
                .orElse(null);
    }

    private static double spacingPenalty(List<LocalDate> dates, int interval) {
        List<LocalDate> sorted = dates.stream().sorted().toList();
        double p = 0;
        for (int i = 1; i < sorted.size(); i++) {
            long diff = ChronoUnit.DAYS.between(sorted.get(i - 1), sorted.get(i));
            p += Math.pow(Math.abs(diff - interval), 2) * 0.3;
        }
        return p;
    }

    static double gapPenalty(LocalDate lastCount, LocalDate date, int minGap, int maxGap) {
        if (lastCount == null) {
            return 0;
        }
        long gap = ChronoUnit.DAYS.between(lastCount, date);
        double p = 0;
        if (gap < minGap) {
            p += Math.pow(minGap - gap, 2) * 0.5;
        } else if (gap > maxGap) {
            p += Math.pow(gap - maxGap, 2) * 0.08;
        }
        // Que no caiga el mismo día del mes que la vez anterior (la supervisora sería predecible).
        if (gap >= 15 && gap <= 50 && Math.abs(date.getDayOfMonth() - lastCount.getDayOfMonth()) <= 2) {
            p += 1.5;
        }
        return p;
    }

    private static boolean brokenRun(List<LocalDate> run) {
        for (int i = 1; i < run.size(); i++) {
            if (ChronoUnit.DAYS.between(run.get(i - 1), run.get(i)) > 3) {
                return true;
            }
        }
        return false;
    }

    /** Una visita de varios días debe ser seguida: se castiga que la partan un fin de semana largo o feriado. */
    private static double brokenRunPenalty(List<LocalDate> run) {
        double p = 0;
        for (int i = 1; i < run.size(); i++) {
            long diff = ChronoUnit.DAYS.between(run.get(i - 1), run.get(i));
            if (diff > 1 && diff <= 3 && run.get(i - 1).getDayOfWeek() != DayOfWeek.FRIDAY) {
                p += 1.0;
            } else if (diff > 3) {
                p += 8.0;
            }
        }
        return p;
    }

    /** Lunes de la semana de una fecha: el calendario se genera de lunes a viernes, una semana a la vez. */
    static LocalDate weekStart(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    /**
     * Un viaje a un departamento no debería partirse por una visita suelta (p. ej. la de Miraflores II) ni cruzar de
     * una semana a otra, porque cada semana se genera por separado. En la capital, partirlo cuesta poco.
     */
    private static double splitPenalty(KioskCountRegion region, List<LocalDate> blockDates, Set<LocalDate> reserved) {
        if (blockDates.size() < 2) {
            return 0;
        }
        boolean metro = region == KioskCountRegion.METROPOLITANA;
        LocalDate first = blockDates.stream().min(LocalDate::compareTo).get();
        LocalDate last = blockDates.stream().max(LocalDate::compareTo).get();
        long inside = reserved.stream().filter(d -> d.isAfter(first) && d.isBefore(last)).count();
        long weekCrossings = ChronoUnit.WEEKS.between(weekStart(first), weekStart(last));
        return inside * (metro ? 0.4 : 4.0) + weekCrossings * (metro ? 0.3 : 3.0);
    }

    /** Que ninguna semana quede casi vacía y otra saturada: cada semana debe llevar su parte de las visitas. */
    private static double weeklyLoadPenalty(Map<Long, List<LocalDate>> assigned, List<LocalDate> free) {
        Map<LocalDate, Integer> freePerWeek = new LinkedHashMap<>();
        free.forEach(d -> freePerWeek.merge(weekStart(d), 1, Integer::sum));
        Map<LocalDate, Integer> visitsPerWeek = new LinkedHashMap<>();
        assigned.values().forEach(list -> list.forEach(d -> visitsPerWeek.merge(weekStart(d), 1, Integer::sum)));
        int totalVisits = visitsPerWeek.values().stream().mapToInt(Integer::intValue).sum();
        int totalFree = freePerWeek.values().stream().mapToInt(Integer::intValue).sum();
        if (totalFree == 0) {
            return 0;
        }
        double p = 0;
        for (Map.Entry<LocalDate, Integer> week : freePerWeek.entrySet()) {
            double target = totalVisits * week.getValue() / (double) totalFree;
            double actual = visitsPerWeek.getOrDefault(week.getKey(), 0);
            p += Math.pow(actual - target, 2) * 0.6;
        }
        return p;
    }
}
