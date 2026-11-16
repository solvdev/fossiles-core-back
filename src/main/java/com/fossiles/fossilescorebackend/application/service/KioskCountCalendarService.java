package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.CalendarResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.GenerateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.KioskRuleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.NextWeekResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.NonWorkingDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.SupervisorResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.VisitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.WeekResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calendario de visitas de conteo físico a kioscos (Contabilidad > Calendario).
 * <p>
 * El calendario se genera <b>una semana a la vez</b> (lunes a viernes), en orden y por supervisora; el sistema
 * recuerda qué semanas ya se generaron para no generarlas dos veces. Cada generación arma el plan completo del mes
 * con {@link KioskCountVisitPlanner} y guarda solo los días de esa semana, así las demás semanas se deciden con
 * lo que realmente pasó antes. Además se pueden mover, quitar y agregar visitas a mano (incluida una revisita:
 * volver a ir a un kiosco que ya tuvo su visita del mes).
 */
@Service
@RequiredArgsConstructor
public class KioskCountCalendarService {

    private static final int ITERATIONS = 4000;
    private static final int LOOKAHEAD_WEEKS = 10;
    private static final Set<String> STATUSES = Set.of("PLANNED", "DONE", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final KioskCountCalendarAccessGuard guard;
    private final SecureRandom random = new SecureRandom();

    // ───────────────────────── consulta ─────────────────────────

    @Transactional(readOnly = true)
    public CalendarResponse getCalendar(String monthText, Long supervisorUserId) throws BusinessException {
        guard.assertCanView();
        return buildCalendar(parseMonth(monthText), supervisorUserId);
    }

    // ───────────────────────── generar una semana ─────────────────────────

    /**
     * Genera la semana de {@code anyDayOfWeek} para cada supervisora indicada. Solo se puede generar la próxima semana
     * pendiente de cada una (en orden) y una semana ya generada no se genera otra vez, salvo {@code redo} mientras
     * la semana no haya empezado (se vuelve a repartir lo pendiente; lo hecho o movido a mano se conserva).
     */
    @Transactional
    public GenerateResponse generateWeek(LocalDate anyDayOfWeek, List<Long> supervisorIds, boolean redo,
                                         String viewMonthText) throws BusinessException {
        Long userId = guard.assertCanGenerate();
        if (anyDayOfWeek == null) {
            throw new BusinessException("Indica la semana.");
        }
        LocalDate today = GuatemalaDateTime.today();
        LocalDate weekStart = KioskCountVisitPlanner.weekStart(anyDayOfWeek);
        LocalDate weekEnd = weekStart.plusDays(4);
        if (weekEnd.isBefore(today)) {
            throw new BusinessException("Esa semana ya pasó.");
        }

        Map<Long, Kiosk> kiosks = loadAssignedKiosks();
        Map<Long, String> supervisorNames = loadSupervisorNames(kiosks.values());
        Map<Long, List<Kiosk>> bySupervisor = new LinkedHashMap<>();
        kiosks.values().forEach(k -> bySupervisor.computeIfAbsent(k.supervisorId, id -> new ArrayList<>()).add(k));
        List<Long> targets = supervisorIds == null || supervisorIds.isEmpty()
                ? new ArrayList<>(bySupervisor.keySet())
                : supervisorIds.stream().filter(bySupervisor::containsKey).toList();
        if (targets.isEmpty()) {
            throw new BusinessException("No hay supervisoras con kioscos asignados.");
        }

        Map<LocalDate, String> nonWorking = nonWorkingBetween(weekStart.minusDays(35), weekStart.plusDays(40));
        Map<Long, LocalDate> lastCounts = loadLastCounts();
        List<String> warnings = new ArrayList<>();

        for (Long supervisorId : targets) {
            String name = supervisorNames.getOrDefault(supervisorId, "Supervisora " + supervisorId);
            boolean exists = weekGenerated(weekStart, supervisorId);
            if (exists && !redo) {
                throw new BusinessException("La semana del " + weekStart + " ya fue generada para " + name
                        + ". Para cambiar fechas usa «Editar calendario».");
            }
            if (redo) {
                if (!exists) {
                    throw new BusinessException("La semana del " + weekStart + " todavía no se ha generado para " + name + ".");
                }
                if (!weekStart.isAfter(today)) {
                    throw new BusinessException("La semana del " + weekStart + " ya empezó; no se puede volver a repartir. "
                            + "Usa «Editar calendario» para mover visitas.");
                }
                jdbc.update("DELETE FROM kiosk_count_visit WHERE supervisor_user_id = ? AND visit_date BETWEEN ? AND ? "
                                + "AND status = 'PLANNED' AND manual = FALSE AND kind = 'REGULAR'",
                        supervisorId, Date.valueOf(weekStart), Date.valueOf(weekEnd));
            } else {
                for (LocalDate w = KioskCountVisitPlanner.weekStart(today); w.isBefore(weekStart); w = w.plusWeeks(1)) {
                    if (hasFreeWorkday(w, today, nonWorking) && !weekGenerated(w, supervisorId)) {
                        throw new BusinessException("Primero genera la semana del " + w + " para " + name
                                + ": las semanas se generan en orden.");
                    }
                }
            }

            List<Kiosk> mine = bySupervisor.get(supervisorId);
            Map<Long, Kiosk> byId = new HashMap<>();
            mine.forEach(k -> byId.put(k.locationId, k));
            int saved = 0;
            boolean unscheduledWarned = false;

            Set<YearMonth> months = new TreeSet<>();
            for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                months.add(YearMonth.from(d));
            }
            for (YearMonth month : months) {
                // Lo que ya existe este mes para esta supervisora: se conserva y ocupa su día.
                List<ExistingVisit> existing = loadVisitsOfMonth(month, supervisorId);
                Map<Long, TreeSet<LocalDate>> kept = new HashMap<>();
                Set<LocalDate> takenDays = new TreeSet<>();
                for (ExistingVisit v : existing) {
                    takenDays.add(v.date);
                    if ("REGULAR".equals(v.kind)) {
                        kept.computeIfAbsent(v.locationId, id -> new TreeSet<>()).add(v.date);
                    }
                }
                // Un conteo físico ya registrado este mes cuenta como visita hecha.
                Map<Long, List<LocalDate>> counted = loadCountsBetween(month.atDay(1), month.atEndOfMonth());
                for (Kiosk k : mine) {
                    for (LocalDate d : counted.getOrDefault(k.locationId, List.of())) {
                        if (!d.isAfter(today)) {
                            kept.computeIfAbsent(k.locationId, id -> new TreeSet<>()).add(d);
                        }
                    }
                }
                List<LocalDate> free = new ArrayList<>();
                for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
                    // Solo desde la semana que se genera: las anteriores ya están cerradas y lo que se les asignara se perdería.
                    if (isWeekday(d) && !nonWorking.containsKey(d) && d.isAfter(today) && !d.isBefore(weekStart)
                            && !takenDays.contains(d)) {
                        free.add(d);
                    }
                }
                if (free.stream().noneMatch(d -> !d.isBefore(weekStart) && !d.isAfter(weekEnd))) {
                    continue;
                }
                List<KioskCountVisitPlanner.Kiosk> input = new ArrayList<>();
                for (Kiosk k : mine) {
                    input.add(new KioskCountVisitPlanner.Kiosk(k.locationId, k.code, k.region, lastCounts.get(k.locationId),
                            k.visitsPerMonth, k.visitDays, k.allowedDays,
                            new ArrayList<>(kept.getOrDefault(k.locationId, new TreeSet<>()))));
                }
                KioskCountVisitPlanner.Plan plan = KioskCountVisitPlanner.plan(input, free, ITERATIONS, random);
                List<KioskCountVisitPlanner.Visit> thisWeek = plan.visits().stream()
                        .filter(v -> !v.date().isBefore(weekStart) && !v.date().isAfter(weekEnd)).toList();
                saved += insertVisits(month, supervisorId, userId, thisWeek, byId);
                if (!plan.unscheduledLocationIds().isEmpty() && !unscheduledWarned) {
                    unscheduledWarned = true;
                    String names = plan.unscheduledLocationIds().stream().distinct()
                            .map(id -> byId.get(id).name).sorted().reduce((a, b) -> a + ", " + b).orElse("");
                    warnings.add(name + ": no alcanzan los días hábiles de " + month + " para " + names
                            + ". Quedarán sin fecha; muévelos o desbloquea días.");
                }
            }
            jdbc.update("INSERT INTO kiosk_count_week (week_start, supervisor_user_id, generated_by) VALUES (?, ?, ?) "
                            + "ON CONFLICT (week_start, supervisor_user_id) DO UPDATE SET generated_by = EXCLUDED.generated_by, "
                            + "generated_at = NOW()",
                    Date.valueOf(weekStart), supervisorId, userId);
            if (saved == 0) {
                warnings.add(name + ": en la semana del " + weekStart + " no quedó ninguna visita por programar.");
            }
        }
        YearMonth view = viewMonthText == null || viewMonthText.isBlank() ? YearMonth.from(weekStart) : parseMonth(viewMonthText);
        return new GenerateResponse(buildCalendar(view, null), warnings);
    }

    private int insertVisits(YearMonth month, Long supervisorId, Long userId, List<KioskCountVisitPlanner.Visit> visits,
                             Map<Long, Kiosk> byId) {
        Map<Long, List<LocalDate>> datesByKiosk = new LinkedHashMap<>();
        visits.forEach(v -> datesByKiosk.computeIfAbsent(v.locationId(), id -> new ArrayList<>()).add(v.date()));
        int count = 0;
        for (Map.Entry<Long, List<LocalDate>> entry : datesByKiosk.entrySet()) {
            Kiosk k = byId.get(entry.getKey());
            List<LocalDate> dates = entry.getValue().stream().sorted().toList();
            int per = Math.max(1, k.visitDays);
            for (int i = 0; i < dates.size(); i++) {
                int chunk = i / per;
                int chunkSize = Math.min(per, dates.size() - chunk * per);
                jdbc.update("INSERT INTO kiosk_count_visit (visit_month, visit_date, location_id, supervisor_user_id, "
                                + "region, status, kind, day_seq, day_total, manual, generated_by) "
                                + "VALUES (?, ?, ?, ?, ?, 'PLANNED', 'REGULAR', ?, ?, FALSE, ?)",
                        month.toString(), Date.valueOf(dates.get(i)), k.locationId, supervisorId, k.region.name(),
                        (i % per) + 1, chunkSize, userId);
                count++;
            }
        }
        return count;
    }

    // ───────────────────────── editar ─────────────────────────

    @Transactional
    public CalendarResponse updateVisit(Long visitId, LocalDate newDate, String status, String note, String viewMonthText)
            throws BusinessException {
        guard.assertCanGenerate();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT visit_month, visit_date, location_id, supervisor_user_id FROM kiosk_count_visit WHERE id = ?",
                visitId);
        if (rows.isEmpty()) {
            throw new BusinessException("No se encontró la visita.");
        }
        Map<String, Object> row = rows.get(0);
        YearMonth month = YearMonth.parse((String) row.get("visit_month"));
        Long locationId = ((Number) row.get("location_id")).longValue();

        if (status != null) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                throw new BusinessException("Estado de visita no válido.");
            }
            jdbc.update("UPDATE kiosk_count_visit SET status = ?, updated_at = NOW() WHERE id = ?", normalized, visitId);
        }
        if (note != null) {
            String trimmed = note.trim();
            jdbc.update("UPDATE kiosk_count_visit SET note = ?, updated_at = NOW() WHERE id = ?",
                    trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(500, trimmed.length())), visitId);
        }
        if (newDate != null) {
            if (!YearMonth.from(newDate).equals(month)) {
                throw new BusinessException("La visita solo se puede mover dentro de " + month + ".");
            }
            assertDayAllowed(newDate, loadAssignedKiosks().get(locationId));
            try {
                jdbc.update("UPDATE kiosk_count_visit SET visit_date = ?, manual = TRUE, updated_at = NOW() WHERE id = ?",
                        Date.valueOf(newDate), visitId);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("Esa supervisora ya tiene otra visita el " + newDate + ".");
            }
        }
        return buildCalendar(viewMonthText == null || viewMonthText.isBlank() ? month : parseMonth(viewMonthText), null);
    }

    /**
     * Agrega una visita a mano. Si el kiosco ya tuvo su visita (o conteo) de ese mes es una revisita (volver a ir) y no
     * sustituye la visita regular del mes; si no, es su visita regular.
     */
    @Transactional
    public CalendarResponse addVisit(Long locationId, LocalDate date, String note, String viewMonthText)
            throws BusinessException {
        Long userId = guard.assertCanGenerate();
        if (locationId == null || date == null) {
            throw new BusinessException("Indica el kiosco y la fecha.");
        }
        Kiosk kiosk = loadAssignedKiosks().get(locationId);
        if (kiosk == null) {
            throw new BusinessException("Ese kiosco no tiene supervisora asignada.");
        }
        if (date.isBefore(GuatemalaDateTime.today())) {
            throw new BusinessException("No se pueden agregar visitas en una fecha que ya pasó.");
        }
        assertDayAllowed(date, kiosk);
        YearMonth month = YearMonth.from(date);
        boolean alreadyVisited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiosk_count_visit WHERE location_id = ? AND visit_month = ? "
                        + "AND status <> 'CANCELLED' AND kind = 'REGULAR'", Integer.class, locationId, month.toString()) > 0
                || !loadCountsBetween(month.atDay(1), month.atEndOfMonth()).getOrDefault(locationId, List.of()).isEmpty();
        String trimmed = note == null ? "" : note.trim();
        try {
            jdbc.update("INSERT INTO kiosk_count_visit (visit_month, visit_date, location_id, supervisor_user_id, region, "
                            + "status, kind, day_seq, day_total, manual, note, generated_by) "
                            + "VALUES (?, ?, ?, ?, ?, 'PLANNED', ?, 1, 1, TRUE, ?, ?)",
                    month.toString(), Date.valueOf(date), locationId, kiosk.supervisorId, kiosk.region.name(),
                    alreadyVisited ? "REVISIT" : "REGULAR",
                    trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(500, trimmed.length())), userId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("Esa supervisora ya tiene otra visita el " + date + ".");
        }
        return buildCalendar(viewMonthText == null || viewMonthText.isBlank() ? month : parseMonth(viewMonthText), null);
    }

    private void assertDayAllowed(LocalDate date, Kiosk kiosk) throws BusinessException {
        if (!isWeekday(date)) {
            throw new BusinessException("Las visitas son de lunes a viernes.");
        }
        Map<LocalDate, String> nonWorking = nonWorkingBetween(date, date);
        if (nonWorking.containsKey(date)) {
            throw new BusinessException("El " + date + " no es día hábil (" + nonWorking.get(date) + ").");
        }
        if (kiosk != null && !kiosk.allowedDays.isEmpty() && !kiosk.allowedDays.contains(date.getDayOfWeek())) {
            throw new BusinessException(kiosk.name + " solo permite visitas en: " + weekdaysLabel(kiosk.allowedDays) + ".");
        }
    }

    // ───────────────────────── días no hábiles ─────────────────────────

    @Transactional
    public CalendarResponse addBlockedDay(LocalDate date, String reason) throws BusinessException {
        Long userId = guard.assertCanGenerate();
        if (date == null) {
            throw new BusinessException("Indica la fecha.");
        }
        String text = reason == null || reason.isBlank() ? "Día no hábil" : reason.trim();
        jdbc.update("INSERT INTO kiosk_count_blocked_day (blocked_date, reason, created_by) VALUES (?, ?, ?) "
                        + "ON CONFLICT (blocked_date) DO UPDATE SET reason = EXCLUDED.reason",
                Date.valueOf(date), text.substring(0, Math.min(200, text.length())), userId);
        return buildCalendar(YearMonth.from(date), null);
    }

    @Transactional
    public CalendarResponse removeBlockedDay(LocalDate date) throws BusinessException {
        guard.assertCanGenerate();
        jdbc.update("DELETE FROM kiosk_count_blocked_day WHERE blocked_date = ?", Date.valueOf(date));
        return buildCalendar(YearMonth.from(date), null);
    }

    // ───────────────────────── armado de la respuesta ─────────────────────────

    private CalendarResponse buildCalendar(YearMonth month, Long supervisorFilter) {
        LocalDate today = GuatemalaDateTime.today();
        LocalDate rangeFrom = KioskCountVisitPlanner.weekStart(month.atDay(1));
        LocalDate rangeTo = KioskCountVisitPlanner.weekStart(month.atEndOfMonth()).plusDays(6);

        Map<Long, Kiosk> kiosks = loadAssignedKiosks();
        Map<Long, String> supervisorNames = loadSupervisorNames(kiosks.values());
        Map<Long, LocalDate> lastCounts = loadLastCounts();

        Map<Long, Integer> kioskCount = new LinkedHashMap<>();
        kiosks.values().forEach(k -> kioskCount.merge(k.supervisorId, 1, Integer::sum));
        List<SupervisorResponse> supervisors = kioskCount.entrySet().stream()
                .map(e -> new SupervisorResponse(e.getKey(), supervisorNames.getOrDefault(e.getKey(), "Supervisora"), e.getValue()))
                .sorted(Comparator.comparing(SupervisorResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<KioskRuleResponse> rules = kiosks.values().stream()
                .sorted(Comparator.comparing((Kiosk k) -> k.name))
                .map(k -> new KioskRuleResponse(k.locationId, k.code, k.name, k.supervisorId, k.region.name(),
                        k.region.label(), lastCounts.get(k.locationId), k.visitsPerMonth, k.visitDays,
                        k.allowedDays.isEmpty() ? null : weekdaysLabel(k.allowedDays)))
                .toList();

        Map<Long, List<LocalDate>> counts = loadCountsBetween(rangeFrom.minusDays(120), rangeTo.plusDays(10));
        List<VisitResponse> visits = new ArrayList<>();
        for (ExistingVisit v : loadVisits(rangeFrom, rangeTo, supervisorFilter)) {
            LocalDate last = null;
            LocalDate countedOn = null;
            for (LocalDate c : counts.getOrDefault(v.locationId, List.of())) {
                if (c.isBefore(v.date) && (last == null || c.isAfter(last))) {
                    last = c;
                }
                if (!c.isBefore(v.date.minusDays(1)) && !c.isAfter(v.date.plusDays(7))
                        && (countedOn == null || c.isBefore(countedOn))) {
                    countedOn = c;
                }
            }
            Integer gap = last == null ? null : (int) ChronoUnit.DAYS.between(last, v.date);
            visits.add(new VisitResponse(v.id, v.date, v.locationId, v.code, v.name, v.supervisorId,
                    supervisorNames.getOrDefault(v.supervisorId, ""), v.region,
                    KioskCountRegion.valueOf(v.region).label(), v.status, v.kind, v.manual, v.note, v.daySeq,
                    v.dayTotal, last, gap, countedOn));
        }

        Map<LocalDate, String> blocked = blockedReasons(rangeFrom, rangeTo);
        List<NonWorkingDayResponse> nonWorking = new ArrayList<>();
        nonWorkingBetween(rangeFrom, rangeTo).forEach((d, reason) ->
                nonWorking.add(new NonWorkingDayResponse(d, reason, blocked.containsKey(d) ? "BLOCKED" : "HOLIDAY")));

        Map<LocalDate, List<Long>> generated = new LinkedHashMap<>();
        jdbc.query("SELECT week_start, supervisor_user_id FROM kiosk_count_week WHERE week_start BETWEEN ? AND ?",
                rs -> {
                    generated.computeIfAbsent(rs.getDate("week_start").toLocalDate(), d -> new ArrayList<>())
                            .add(rs.getLong("supervisor_user_id"));
                }, Date.valueOf(rangeFrom), Date.valueOf(rangeTo));
        List<WeekResponse> weeks = new ArrayList<>();
        for (LocalDate w = rangeFrom; !w.isAfter(rangeTo); w = w.plusWeeks(1)) {
            List<Long> ids = generated.getOrDefault(w, List.of());
            weeks.add(new WeekResponse(w, w.plusDays(4), ids,
                    !supervisors.isEmpty() && supervisors.stream().allMatch(s -> ids.contains(s.userId())),
                    !w.isAfter(today)));
        }

        Map<LocalDate, String> horizon = nonWorkingBetween(today, today.plusWeeks(LOOKAHEAD_WEEKS + 2L));
        List<NextWeekResponse> nextWeeks = new ArrayList<>();
        for (SupervisorResponse s : supervisors) {
            LocalDate w = KioskCountVisitPlanner.weekStart(today);
            for (int i = 0; i < LOOKAHEAD_WEEKS; i++, w = w.plusWeeks(1)) {
                if (hasFreeWorkday(w, today, horizon) && !weekGenerated(w, s.userId())) {
                    nextWeeks.add(new NextWeekResponse(s.userId(), w, w.plusDays(4)));
                    break;
                }
            }
        }

        List<String> unassigned = jdbc.queryForList(
                "SELECT l.name FROM locations l LEFT JOIN kiosk_site s ON s.location_id = l.id "
                        + "WHERE l.categoria = 'KIOSKO' AND COALESCE(s.status, 'ACTIVE') = 'ACTIVE' "
                        + "AND NOT EXISTS (SELECT 1 FROM kiosk_supervisor_assignment a WHERE a.kiosk_location_id = l.id) "
                        + "ORDER BY l.name", String.class);

        boolean canGenerate;
        try {
            guard.assertCanGenerate();
            canGenerate = true;
        } catch (BusinessException e) {
            canGenerate = false;
        }
        return new CalendarResponse(month.toString(), today, supervisors, rules, visits, nonWorking, weeks, nextWeeks,
                unassigned, canGenerate);
    }

    // ───────────────────────── datos ─────────────────────────

    private static final class Kiosk {
        long locationId;
        String code;
        String name;
        KioskCountRegion region;
        long supervisorId;
        int visitsPerMonth = 1;
        int visitDays = 1;
        Set<DayOfWeek> allowedDays = EnumSet.noneOf(DayOfWeek.class);
    }

    private record ExistingVisit(long id, LocalDate date, long locationId, String code, String name, long supervisorId,
                                 String region, String status, String kind, boolean manual, String note, int daySeq,
                                 int dayTotal) {
    }

    /** Kioscos activos con supervisora asignada, con sus reglas propias. Si tiene dos supervisoras, gana la primera. */
    private Map<Long, Kiosk> loadAssignedKiosks() {
        Map<Long, Kiosk> result = new LinkedHashMap<>();
        jdbc.query("SELECT l.id, l.code, l.name, l.departamento, l.municipio, a.supervisor_user_id, "
                + "c.visits_per_month, c.visit_days, c.allowed_weekdays "
                + "FROM locations l "
                + "JOIN kiosk_supervisor_assignment a ON a.kiosk_location_id = l.id "
                + "LEFT JOIN kiosk_site s ON s.location_id = l.id "
                + "LEFT JOIN kiosk_count_visit_config c ON c.location_id = l.id "
                + "WHERE l.categoria = 'KIOSKO' AND COALESCE(s.status, 'ACTIVE') = 'ACTIVE' "
                + "ORDER BY l.id, a.supervisor_user_id", rs -> {
            long id = rs.getLong("id");
            if (result.containsKey(id)) {
                return;
            }
            Kiosk k = new Kiosk();
            k.locationId = id;
            k.code = rs.getString("code");
            k.name = displayName(rs.getString("name"));
            k.region = KioskCountRegion.of(rs.getString("departamento"), rs.getString("municipio"));
            k.supervisorId = rs.getLong("supervisor_user_id");
            if (rs.getObject("visits_per_month") != null) {
                k.visitsPerMonth = rs.getInt("visits_per_month");
                k.visitDays = rs.getInt("visit_days");
                k.allowedDays = parseWeekdays(rs.getString("allowed_weekdays"));
            }
            result.put(id, k);
        });
        return result;
    }

    private Map<Long, String> loadSupervisorNames(Collection<Kiosk> kiosks) {
        Map<Long, String> names = new HashMap<>();
        Set<Long> ids = new TreeSet<>();
        kiosks.forEach(k -> ids.add(k.supervisorId));
        for (Long id : ids) {
            jdbc.query("SELECT first_name, last_name, username FROM users WHERE id = ?", rs -> {
                String full = ((rs.getString("first_name") == null ? "" : rs.getString("first_name")) + " "
                        + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim();
                names.put(id, full.isEmpty() ? rs.getString("username") : full);
            }, id);
        }
        return names;
    }

    /** Fin del último conteo físico de cada kiosco (sin contar borradores; si solo hay borradores, el más reciente). */
    private Map<Long, LocalDate> loadLastCounts() {
        Map<Long, LocalDate> result = new HashMap<>();
        jdbc.query("SELECT location_id, COALESCE(MAX(period_to) FILTER (WHERE status <> 'DRAFT'), MAX(period_to)) AS last_to "
                + "FROM kiosco_physical_count GROUP BY location_id", rs -> {
            Date d = rs.getDate("last_to");
            if (d != null) {
                result.put(rs.getLong("location_id"), d.toLocalDate());
            }
        });
        return result;
    }

    private Map<Long, List<LocalDate>> loadCountsBetween(LocalDate from, LocalDate to) {
        Map<Long, List<LocalDate>> result = new HashMap<>();
        jdbc.query("SELECT location_id, period_to FROM kiosco_physical_count "
                        + "WHERE status <> 'DRAFT' AND period_to BETWEEN ? AND ? ORDER BY period_to",
                rs -> {
                    result.computeIfAbsent(rs.getLong("location_id"), id -> new ArrayList<>())
                            .add(rs.getDate("period_to").toLocalDate());
                }, Date.valueOf(from), Date.valueOf(to));
        return result;
    }

    private static final String VISIT_SELECT = "SELECT v.id, v.visit_date, v.location_id, l.code, l.name, "
            + "v.supervisor_user_id, v.region, v.status, v.kind, v.manual, v.note, v.day_seq, v.day_total "
            + "FROM kiosk_count_visit v JOIN locations l ON l.id = v.location_id WHERE v.status <> 'CANCELLED' ";

    private List<ExistingVisit> loadVisits(LocalDate from, LocalDate to, Long supervisorId) {
        String sql = VISIT_SELECT + "AND v.visit_date BETWEEN ? AND ? "
                + (supervisorId == null ? "" : "AND v.supervisor_user_id = ? ")
                + "ORDER BY v.visit_date, v.supervisor_user_id";
        Object[] args = supervisorId == null ? new Object[]{Date.valueOf(from), Date.valueOf(to)}
                : new Object[]{Date.valueOf(from), Date.valueOf(to), supervisorId};
        return jdbc.query(sql, (rs, i) -> mapVisit(rs), args);
    }

    private List<ExistingVisit> loadVisitsOfMonth(YearMonth month, Long supervisorId) {
        return jdbc.query(VISIT_SELECT + "AND v.visit_month = ? AND v.supervisor_user_id = ? ORDER BY v.visit_date",
                (rs, i) -> mapVisit(rs), month.toString(), supervisorId);
    }

    private ExistingVisit mapVisit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExistingVisit(rs.getLong("id"), rs.getDate("visit_date").toLocalDate(), rs.getLong("location_id"),
                rs.getString("code"), displayName(rs.getString("name")), rs.getLong("supervisor_user_id"),
                rs.getString("region"), rs.getString("status"), rs.getString("kind"), rs.getBoolean("manual"),
                rs.getString("note"), rs.getInt("day_seq"), rs.getInt("day_total"));
    }

    private boolean weekGenerated(LocalDate weekStart, Long supervisorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kiosk_count_week WHERE week_start = ? AND supervisor_user_id = ?",
                Integer.class, Date.valueOf(weekStart), supervisorId);
        return n != null && n > 0;
    }

    private Map<LocalDate, String> blockedReasons(LocalDate from, LocalDate to) {
        Map<LocalDate, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT blocked_date, reason FROM kiosk_count_blocked_day WHERE blocked_date BETWEEN ? AND ? "
                        + "ORDER BY blocked_date",
                rs -> {
                    result.put(rs.getDate("blocked_date").toLocalDate(), rs.getString("reason"));
                }, Date.valueOf(from), Date.valueOf(to));
        return result;
    }

    /** Feriados nacionales (de día completo) y días bloqueados a mano entre dos fechas. */
    Map<LocalDate, String> nonWorkingBetween(LocalDate from, LocalDate to) {
        Map<LocalDate, String> result = new java.util.TreeMap<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            GuatemalaHolidays.forYear(year).stream()
                    .filter(h -> !h.halfDay() && !h.date().isBefore(from) && !h.date().isAfter(to))
                    .forEach(h -> result.put(h.date(), h.name()));
        }
        result.putAll(blockedReasons(from, to));
        return result;
    }

    private static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** ¿La semana tiene aún algún día hábil por delante (después de hoy)? */
    private static boolean hasFreeWorkday(LocalDate weekStart, LocalDate today, Map<LocalDate, String> nonWorking) {
        for (int i = 0; i < 5; i++) {
            LocalDate d = weekStart.plusDays(i);
            if (d.isAfter(today) && !nonWorking.containsKey(d)) {
                return true;
            }
        }
        return false;
    }

    // ───────────────────────── utilidades ─────────────────────────

    private static YearMonth parseMonth(String text) throws BusinessException {
        if (text == null || text.isBlank()) {
            return YearMonth.from(GuatemalaDateTime.today());
        }
        try {
            return YearMonth.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("Mes no válido. Usa el formato AAAA-MM.");
        }
    }

    private static String displayName(String name) {
        return name == null ? "" : name.replaceFirst("(?i)^CUEROGLAM\\s+", "").trim();
    }

    static Set<DayOfWeek> parseWeekdays(String text) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (text == null) {
            return days;
        }
        for (String part : text.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (p.length() >= 3) {
                for (DayOfWeek d : DayOfWeek.values()) {
                    if (d.name().startsWith(p.substring(0, 3))) {
                        days.add(d);
                    }
                }
            }
        }
        return days;
    }

    static String weekdaysLabel(Set<DayOfWeek> days) {
        String[] names = {"lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
        List<String> parts = new ArrayList<>();
        days.stream().sorted().forEach(d -> parts.add(names[d.getValue() - 1]));
        return String.join(", ", parts);
    }
}
