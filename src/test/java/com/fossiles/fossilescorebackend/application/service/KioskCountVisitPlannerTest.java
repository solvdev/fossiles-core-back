package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.service.KioskCountVisitPlanner.Kiosk;
import com.fossiles.fossilescorebackend.application.service.KioskCountVisitPlanner.Plan;
import com.fossiles.fossilescorebackend.application.service.KioskCountVisitPlanner.Visit;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KioskCountVisitPlannerTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 12);

    private static List<LocalDate> workdays(YearMonth month) {
        List<LocalDate> days = new ArrayList<>();
        Set<LocalDate> holidays = GuatemalaHolidays.forYear(month.getYear()).stream()
                .filter(h -> !h.halfDay()).map(GuatemalaHolidays.Holiday::date).collect(Collectors.toSet());
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5 && !holidays.contains(d)) {
                days.add(d);
            }
        }
        return days;
    }

    private static Kiosk k(long id, String code, KioskCountRegion region, String last) {
        return new Kiosk(id, code, region, last == null ? null : LocalDate.parse(last), 1, 1,
                EnumSet.noneOf(DayOfWeek.class), List.of());
    }

    /** Los 18 kioscos de la supervisora con más variedad: capital, occidente, sur, con las reglas especiales. */
    private static List<Kiosk> anitaKiosks() {
        List<Kiosk> list = new ArrayList<>();
        KioskCountRegion m = KioskCountRegion.METROPOLITANA;
        list.add(k(1, "PORTALES", m, "2026-11-08"));
        list.add(k(2, "PRAVIST", m, "2026-11-03"));
        list.add(k(3, "ZONA4", m, "2026-10-22"));
        list.add(new Kiosk(4, "MIRAF2", m, LocalDate.parse("2026-11-10"), 2, 1, EnumSet.noneOf(DayOfWeek.class), List.of()));
        list.add(new Kiosk(5, "NARANJO", m, LocalDate.parse("2026-11-09"), 1, 1,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), List.of()));
        list.add(k(6, "RUSMALL", m, "2026-10-20"));
        list.add(k(7, "PRACONC", m, "2026-11-03"));
        list.add(k(8, "ESKALA", m, "2026-10-18"));
        list.add(k(9, "INT_VLOBOS", m, "2026-11-01"));
        list.add(k(10, "SANTACLR", m, "2026-11-05"));
        list.add(k(11, "ELFRUTAL", m, "2026-11-04"));
        list.add(new Kiosk(12, "ETRC", m, LocalDate.parse("2026-11-11"), 1, 2, EnumSet.noneOf(DayOfWeek.class), List.of()));
        list.add(k(13, "COATEPEQ", KioskCountRegion.OCCIDENTE, "2026-10-26"));
        list.add(k(14, "INT_XELA", KioskCountRegion.OCCIDENTE, "2026-10-15"));
        list.add(k(15, "RETALHU", KioskCountRegion.OCCIDENTE, "2026-10-25"));
        list.add(k(16, "UTZULEW", KioskCountRegion.OCCIDENTE, "2026-10-14"));
        list.add(k(17, "MAZATEN", KioskCountRegion.OCCIDENTE, "2026-10-27"));
        list.add(k(18, "SANLUCAS", KioskCountRegion.CENTRAL, "2026-11-02"));
        return list;
    }

    @Test
    void respetaReglasDuras() {
        for (long seed = 1; seed <= 25; seed++) {
            Plan plan = KioskCountVisitPlanner.plan(anitaKiosks(), workdays(MONTH), 1500, new Random(seed));
            assertTrue(plan.unscheduledLocationIds().isEmpty(), "sin kioscos sin fecha (semilla " + seed + ")");

            // una visita por día
            Set<LocalDate> dates = new HashSet<>();
            plan.visits().forEach(v -> assertTrue(dates.add(v.date()), "día repetido " + v.date()));

            Map<Long, List<LocalDate>> byKiosk = plan.visits().stream().collect(
                    Collectors.groupingBy(Visit::locationId, Collectors.mapping(Visit::date, Collectors.toList())));
            assertEquals(18, byKiosk.size());

            // Miraflores II: dos visitas separadas ~15 días
            List<LocalDate> miraf = byKiosk.get(4L).stream().sorted().toList();
            assertEquals(2, miraf.size());
            long between = ChronoUnit.DAYS.between(miraf.get(0), miraf.get(1));
            assertTrue(between >= 13 && between <= 17, "Miraflores cada ~15 días, fue " + between);

            // Naranjo solo lunes a miércoles
            byKiosk.get(5L).forEach(d -> assertTrue(d.getDayOfWeek().getValue() <= 3, "Naranjo en " + d.getDayOfWeek()));

            // Entrecueros: dos días seguidos
            List<LocalDate> etrc = byKiosk.get(12L).stream().sorted().toList();
            assertEquals(2, etrc.size());
            assertTrue(ChronoUnit.DAYS.between(etrc.get(0), etrc.get(1)) <= 3);
        }
    }

    @Test
    void agrupaPorRegionYVariaEntreMeses() {
        Plan first = KioskCountVisitPlanner.plan(anitaKiosks(), workdays(MONTH), 3000, new Random(7));
        // Occidente (5 kioscos) debe quedar en días consecutivos sin intercalar otra región.
        List<Visit> sorted = first.visits();
        Map<Long, KioskCountRegion> region = anitaKiosks().stream()
                .collect(Collectors.toMap(Kiosk::locationId, Kiosk::region));
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (region.get(sorted.get(i).locationId()) == KioskCountRegion.OCCIDENTE) {
                idx.add(i);
            }
        }
        assertEquals(5, idx.size());
        assertTrue(idx.get(idx.size() - 1) - idx.get(0) <= 5, "Occidente seguido, índices " + idx);

        // Otra semilla da otro calendario (no es el mismo mes tras mes).
        Plan second = KioskCountVisitPlanner.plan(anitaKiosks(), workdays(MONTH), 3000, new Random(8));
        assertTrue(!first.visits().equals(second.visits()));
    }

    @Test
    void conservaVisitasYReparteElResto() {
        List<Kiosk> kiosks = new ArrayList<>(anitaKiosks());
        // Portales ya fue visitado el 2-dic (queda fijo) y Miraflores ya tuvo su primera visita el 3-dic.
        kiosks.set(0, new Kiosk(1, "PORTALES", KioskCountRegion.METROPOLITANA, LocalDate.parse("2026-11-08"), 1, 1,
                EnumSet.noneOf(DayOfWeek.class), List.of(LocalDate.of(2026, 12, 2))));
        kiosks.set(3, new Kiosk(4, "MIRAF2", KioskCountRegion.METROPOLITANA, LocalDate.parse("2026-11-10"), 2, 1,
                EnumSet.noneOf(DayOfWeek.class), List.of(LocalDate.of(2026, 12, 3))));
        List<LocalDate> free = workdays(MONTH).stream()
                .filter(d -> !d.equals(LocalDate.of(2026, 12, 2)) && !d.equals(LocalDate.of(2026, 12, 3))).toList();
        Plan plan = KioskCountVisitPlanner.plan(kiosks, free, 1500, new Random(3));

        assertTrue(plan.visits().stream().noneMatch(v -> v.locationId() == 1L), "Portales ya está hecho");
        List<LocalDate> miraf = plan.visits().stream().filter(v -> v.locationId() == 4L).map(Visit::date).toList();
        assertEquals(1, miraf.size());
        long between = ChronoUnit.DAYS.between(LocalDate.of(2026, 12, 3), miraf.get(0));
        assertTrue(between >= 13 && between <= 17, "segunda visita ~15 días después, fue " + between);
    }

    @Test
    void avisaCuandoNoAlcanzanLosDias() {
        Plan plan = KioskCountVisitPlanner.plan(anitaKiosks(), workdays(MONTH).subList(0, 10), 200, new Random(1));
        assertTrue(!plan.unscheduledLocationIds().isEmpty());
    }

    @Test
    void generaSemanaASemanaSinRepetirNiDejarKioscosFuera() {
        for (long seed = 1; seed <= 15; seed++) {
            final long theSeed = seed;
            Random rnd = new Random(seed);
            List<LocalDate> workdays = workdays(MONTH);
            Map<Long, List<LocalDate>> keptByKiosk = new java.util.HashMap<>();
            Set<LocalDate> taken = new HashSet<>();
            Map<LocalDate, Integer> perWeek = new java.util.TreeMap<>();
            List<LocalDate> weeks = workdays.stream().map(KioskCountVisitPlanner::weekStart).distinct().sorted().toList();

            for (LocalDate week : weeks) {
                List<Kiosk> input = new ArrayList<>();
                for (Kiosk k : anitaKiosks()) {
                    input.add(new Kiosk(k.locationId(), k.code(), k.region(), k.lastCount(), k.visitsPerMonth(),
                            k.visitDays(), k.allowedDays(), new ArrayList<>(keptByKiosk.getOrDefault(k.locationId(), List.of()))));
                }
                List<LocalDate> free = workdays.stream().filter(d -> !taken.contains(d) && !d.isBefore(week)).toList();
                Plan plan = KioskCountVisitPlanner.plan(input, free, 800, rnd);
                for (Visit v : plan.visits()) {
                    if (KioskCountVisitPlanner.weekStart(v.date()).equals(week)) {
                        keptByKiosk.computeIfAbsent(v.locationId(), id -> new ArrayList<>()).add(v.date());
                        assertTrue(taken.add(v.date()), "día repetido " + v.date() + " (semilla " + theSeed + ")");
                        perWeek.merge(week, 1, Integer::sum);
                    }
                }
            }
            // Todos los kioscos quedaron con su visita del mes (Miraflores 2, ETRC 2 días, Naranjo 1, resto 1).
            assertEquals(18, keptByKiosk.size(), "semilla " + theSeed);
            assertEquals(2, keptByKiosk.get(4L).size(), "Miraflores dos visitas");
            assertEquals(2, keptByKiosk.get(12L).size(), "Entrecueros dos días");
            keptByKiosk.get(5L).forEach(d -> assertTrue(d.getDayOfWeek().getValue() <= 3, "Naranjo lunes a miércoles"));
            // Ninguna semana queda vacía ni saturada.
            perWeek.values().forEach(n -> assertTrue(n >= 2 && n <= 6, "carga por semana " + perWeek + " semilla " + theSeed));
        }
    }

    @Test
    void regionesYFeriados() {
        assertEquals(KioskCountRegion.SUR, KioskCountRegion.of("Guatemala", "Santa Lucía Cotzumalguapa"));
        assertEquals(KioskCountRegion.METROPOLITANA, KioskCountRegion.of("GUATEMALA", "GUATEMALA"));
        assertEquals(KioskCountRegion.OCCIDENTE, KioskCountRegion.of("Quiche", "Santa Cruz"));
        assertEquals(KioskCountRegion.OCCIDENTE, KioskCountRegion.of("Sololá", "Panajachel"));
        assertEquals(KioskCountRegion.CENTRAL, KioskCountRegion.of("Sacatepéquez", "San Lucas Sacatepéquez"));
        assertEquals(KioskCountRegion.NORTE, KioskCountRegion.of("Alta Verapaz", "Cobán"));
        assertEquals(KioskCountRegion.ORIENTE, KioskCountRegion.of("Jutiapa", "Jutiapa"));
        assertTrue(GuatemalaHolidays.isHoliday(LocalDate.of(2027, 3, 25)), "Jueves Santo 2027");
    }
}
