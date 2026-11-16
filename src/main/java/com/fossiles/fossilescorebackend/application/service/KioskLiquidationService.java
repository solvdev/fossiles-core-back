package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationGenerateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationGroup;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationWeekResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.PendingCount;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Liquidación semanal de los conteos: la revisión de contabilidad de cada conteo físico (si salió bien el conteo,
 * depósitos, cambios, facturas verdes), unas 4 semanas después de la visita. Se arma por semana, un kiosco por día,
 * como en el Excel «Liquidaciones por semana».
 */
@Service
@RequiredArgsConstructor
public class KioskLiquidationService {

    private static final int CORRECTIONS_DAYS_AFTER_COUNT = 2;
    private static final int MIN_DAYS_BETWEEN_COUNTS = 5;

    private final JdbcTemplate jdbc;
    private final KioskCountCalendarAccessGuard guard;
    private final KioskCountCalendarService calendarService;

    public record UpdateRequest(String status, Integer areasToFix, Boolean clearAreas, String accountant,
                                LocalDate correctionsDue, LocalDate liquidationDate, Integer liquidationDays,
                                String greenNote, String note) {
    }

    // ───────────────────────── consulta ─────────────────────────

    @Transactional(readOnly = true)
    public LiquidationWeekResponse getWeek(LocalDate anyDay, Long supervisorUserId) throws BusinessException {
        guard.assertCanView();
        return buildWeek(weekStart(anyDay), supervisorUserId);
    }

    // ───────────────────────── generar ─────────────────────────

    /**
     * Pone en los días libres de la semana los conteos que todavía no tienen fecha de liquidación, del más viejo al más
     * reciente. Se puede pulsar otra vez sin duplicar nada: solo llena los días que sigan libres.
     */
    @Transactional
    public LiquidationGenerateResponse generateWeek(LocalDate anyDay, List<Long> supervisorIds) throws BusinessException {
        Long userId = guard.assertCanGenerate();
        LocalDate today = GuatemalaDateTime.today();
        LocalDate weekStart = weekStart(anyDay);
        if (weekStart.plusDays(4).isBefore(today)) {
            throw new BusinessException("Esa semana ya pasó.");
        }
        Map<LocalDate, String> nonWorking = calendarService.nonWorkingBetween(weekStart, weekStart.plusDays(6));
        List<LocalDate> weekDays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate d = weekStart.plusDays(i);
            if (!d.isBefore(today) && !nonWorking.containsKey(d)) {
                weekDays.add(d);
            }
        }
        Map<Long, String> supervisorNames = loadSupervisorNames();
        List<Long> targets = supervisorIds == null || supervisorIds.isEmpty()
                ? new ArrayList<>(supervisorNames.keySet())
                : supervisorIds.stream().filter(supervisorNames::containsKey).toList();
        if (targets.isEmpty()) {
            throw new BusinessException("No hay supervisoras con kioscos asignados.");
        }
        List<String> warnings = new ArrayList<>();
        if (weekDays.isEmpty()) {
            warnings.add("Esa semana no tiene días hábiles libres.");
        }
        Map<Long, Integer> visitDays = loadVisitDays();
        Map<Long, String> lastAccountant = loadLastAccountants();

        for (Long supervisorId : targets) {
            String name = supervisorNames.get(supervisorId);
            Set<LocalDate> occupied = new HashSet<>();
            for (LiquidationRow row : loadRows(weekStart, weekStart.plusDays(6), supervisorId)) {
                for (int d = 0; d < row.liquidationDays(); d++) {
                    occupied.add(row.liquidationDate().plusDays(d));
                }
            }
            List<PendingCount> pending = loadPending(supervisorId);
            List<KioskLiquidationPlanner.Pending> input = pending.stream()
                    .sorted(Comparator.comparing(PendingCount::periodTo))
                    .map(p -> new KioskLiquidationPlanner.Pending(p.locationId(), p.periodTo(),
                            visitDays.getOrDefault(p.locationId(), 1)))
                    .toList();
            List<KioskLiquidationPlanner.Slot> slots = KioskLiquidationPlanner.assign(weekDays, occupied, input,
                    KioskLiquidationPlanner.MIN_LEAD_DAYS);
            for (KioskLiquidationPlanner.Slot s : slots) {
                jdbc.update("INSERT INTO kiosk_liquidation (location_id, supervisor_user_id, period_from, period_to, accountant, "
                                + "corrections_due, liquidation_date, liquidation_days, status, source, created_by) "
                                + "VALUES (?, ?, (SELECT MIN(x.period_from) FROM (SELECT location_id, period_to, period_from FROM kiosco_physical_count "
                                + "UNION ALL SELECT location_id, period_to, period_from FROM kiosk_count_history) x "
                                + "WHERE x.location_id = ? AND x.period_to = ?), "
                                + "?, ?, ?, ?, ?, 'PENDING', 'GENERATED', ?) "
                                + "ON CONFLICT (location_id, period_to) DO NOTHING",
                        s.locationId(), supervisorId, s.locationId(), Date.valueOf(s.periodTo()), Date.valueOf(s.periodTo()),
                        lastAccountant.get(s.locationId()), Date.valueOf(s.periodTo().plusDays(CORRECTIONS_DAYS_AFTER_COUNT)),
                        Date.valueOf(s.date()), s.days(), userId);
            }
            int left = pending.size() - slots.size();
            boolean full = weekDays.stream().allMatch(occupied::contains);
            if (slots.isEmpty() && full) {
                warnings.add(name + ": la semana ya tiene liquidación todos sus días.");
            } else if (slots.isEmpty()) {
                warnings.add(name + ": no hay conteos por liquidar que quepan en esa semana"
                        + (pending.isEmpty() ? " (todo está liquidado o con fecha)." : " (los conteos recientes esperan " + KioskLiquidationPlanner.MIN_LEAD_DAYS + " días)."));
            } else if (left > 0) {
                warnings.add(name + ": quedan " + left + " conteo(s) por liquidar para las siguientes semanas.");
            }
        }
        return new LiquidationGenerateResponse(buildWeek(weekStart, null), warnings);
    }

    // ───────────────────────── editar ─────────────────────────

    @Transactional
    public LiquidationWeekResponse update(Long id, UpdateRequest req, LocalDate viewDay) throws BusinessException {
        guard.assertCanGenerate();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT liquidation_date FROM kiosk_liquidation WHERE id = ?", id);
        if (found.isEmpty()) {
            throw new BusinessException("No se encontró la liquidación.");
        }
        if (req.status() != null) {
            String status = req.status().trim().toUpperCase(Locale.ROOT);
            if (!status.equals("PENDING") && !status.equals("LIQUIDATED")) {
                throw new BusinessException("Estado no válido.");
            }
            jdbc.update("UPDATE kiosk_liquidation SET status = ?, updated_at = NOW() WHERE id = ?", status, id);
        }
        if (Boolean.TRUE.equals(req.clearAreas())) {
            jdbc.update("UPDATE kiosk_liquidation SET areas_to_fix = NULL, updated_at = NOW() WHERE id = ?", id);
        } else if (req.areasToFix() != null) {
            if (req.areasToFix() < 0 || req.areasToFix() > 99) {
                throw new BusinessException("Las áreas por corregir deben estar entre 0 y 99.");
            }
            jdbc.update("UPDATE kiosk_liquidation SET areas_to_fix = ?, updated_at = NOW() WHERE id = ?", req.areasToFix(), id);
        }
        if (req.accountant() != null) {
            jdbc.update("UPDATE kiosk_liquidation SET accountant = ?, updated_at = NOW() WHERE id = ?",
                    blankToNull(req.accountant(), 80), id);
        }
        if (req.correctionsDue() != null) {
            jdbc.update("UPDATE kiosk_liquidation SET corrections_due = ?, updated_at = NOW() WHERE id = ?",
                    Date.valueOf(req.correctionsDue()), id);
        }
        if (req.liquidationDate() != null) {
            jdbc.update("UPDATE kiosk_liquidation SET liquidation_date = ?, updated_at = NOW() WHERE id = ?",
                    Date.valueOf(req.liquidationDate()), id);
        }
        if (req.liquidationDays() != null) {
            if (req.liquidationDays() < 1 || req.liquidationDays() > 5) {
                throw new BusinessException("Los días de liquidación deben estar entre 1 y 5.");
            }
            jdbc.update("UPDATE kiosk_liquidation SET liquidation_days = ?, updated_at = NOW() WHERE id = ?", req.liquidationDays(), id);
        }
        if (req.greenNote() != null) {
            jdbc.update("UPDATE kiosk_liquidation SET green_note = ?, updated_at = NOW() WHERE id = ?",
                    blankToNull(req.greenNote(), 300), id);
        }
        if (req.note() != null) {
            jdbc.update("UPDATE kiosk_liquidation SET note = ?, updated_at = NOW() WHERE id = ?",
                    blankToNull(req.note(), 500), id);
        }
        LocalDate view = viewDay != null ? viewDay : ((Date) found.get(0).get("liquidation_date")).toLocalDate();
        return buildWeek(weekStart(view), null);
    }

    // ───────────────────────── armado ─────────────────────────

    private LiquidationWeekResponse buildWeek(LocalDate weekStart, Long supervisorFilter) {
        Map<Long, String> names = loadSupervisorNames();
        Map<Long, List<LiquidationRow>> byWho = new LinkedHashMap<>();
        names.keySet().stream()
                .sorted(Comparator.comparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER))
                .filter(id -> supervisorFilter == null || supervisorFilter.equals(id))
                .forEach(id -> byWho.put(id, new ArrayList<>()));
        for (LiquidationRow row : loadRows(weekStart, weekStart.plusDays(6), supervisorFilter)) {
            byWho.computeIfAbsent(row.supervisorUserId(), id -> new ArrayList<>()).add(row);
        }
        List<LiquidationGroup> groups = new ArrayList<>();
        byWho.forEach((id, rows) -> groups.add(new LiquidationGroup(id, names.getOrDefault(id, "Supervisora"), rows)));

        List<PendingCount> pending = new ArrayList<>();
        for (Long id : byWho.keySet()) {
            pending.addAll(loadPending(id));
        }
        pending.sort(Comparator.comparing(PendingCount::periodTo));

        List<String> accountants = jdbc.queryForList(
                "SELECT accountant FROM kiosk_liquidation WHERE accountant IS NOT NULL AND TRIM(accountant) <> '' "
                        + "GROUP BY accountant ORDER BY COUNT(*) DESC LIMIT 15", String.class);
        boolean canGenerate;
        try {
            guard.assertCanGenerate();
            canGenerate = true;
        } catch (BusinessException e) {
            canGenerate = false;
        }
        return new LiquidationWeekResponse(weekStart, weekStart.plusDays(6), groups, pending, accountants, canGenerate);
    }

    private List<LiquidationRow> loadRows(LocalDate from, LocalDate to, Long supervisorId) {
        String sql = "SELECT q.id, q.location_id, l.name, q.supervisor_user_id, q.period_from, q.period_to, q.accountant, "
                + "q.corrections_due, q.liquidation_date, q.liquidation_days, q.areas_to_fix, q.green_note, q.status, q.note, q.source "
                + "FROM kiosk_liquidation q JOIN locations l ON l.id = q.location_id "
                + "WHERE q.liquidation_date BETWEEN ? AND ? " + (supervisorId == null ? "" : "AND q.supervisor_user_id = ? ")
                + "ORDER BY q.liquidation_date, q.id";
        Object[] args = supervisorId == null ? new Object[]{Date.valueOf(from), Date.valueOf(to)}
                : new Object[]{Date.valueOf(from), Date.valueOf(to), supervisorId};
        Map<Long, String> names = loadSupervisorNames();
        return jdbc.query(sql, (rs, i) -> new LiquidationRow(rs.getLong("id"), rs.getLong("location_id"),
                displayName(rs.getString("name")), rs.getLong("supervisor_user_id"),
                names.getOrDefault(rs.getLong("supervisor_user_id"), ""),
                localDate(rs.getDate("period_from")), rs.getDate("period_to").toLocalDate(), rs.getString("accountant"),
                localDate(rs.getDate("corrections_due")), rs.getDate("liquidation_date").toLocalDate(),
                rs.getInt("liquidation_days"), (Integer) rs.getObject("areas_to_fix"), rs.getString("green_note"),
                rs.getString("status"), rs.getString("note"), rs.getString("source")), args);
    }

    /** Conteos hechos (Excel o sistema) de una supervisora que todavía no tienen fecha de liquidación. */
    private List<PendingCount> loadPending(Long supervisorId) {
        return jdbc.query("SELECT c.location_id, l.name, c.period_to FROM ("
                        + "SELECT location_id, period_to FROM kiosk_count_history "
                        + "UNION SELECT location_id, period_to FROM kiosco_physical_count WHERE status <> 'DRAFT') c "
                        + "JOIN locations l ON l.id = c.location_id "
                        + "JOIN kiosk_supervisor_assignment a ON a.kiosk_location_id = c.location_id AND a.supervisor_user_id = ? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM kiosk_liquidation q WHERE q.location_id = c.location_id AND q.period_to = c.period_to) "
                        + "AND c.period_to > COALESCE((SELECT MAX(q.period_to) FROM kiosk_liquidation q WHERE q.location_id = c.location_id), "
                        + "DATE '1900-01-01') + " + MIN_DAYS_BETWEEN_COUNTS + " "
                        + "ORDER BY c.period_to",
                (rs, i) -> new PendingCount(rs.getLong("location_id"), displayName(rs.getString("name")), supervisorId,
                        rs.getDate("period_to").toLocalDate()), supervisorId);
    }

    private Map<Long, String> loadSupervisorNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT u.id, u.first_name, u.last_name, u.username FROM users u "
                + "JOIN kiosk_supervisor_assignment a ON a.supervisor_user_id = u.id", rs -> {
            String full = ((rs.getString("first_name") == null ? "" : rs.getString("first_name")) + " "
                    + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim();
            names.put(rs.getLong("id"), full.isEmpty() ? rs.getString("username") : full);
        });
        return names;
    }

    private Map<Long, Integer> loadVisitDays() {
        Map<Long, Integer> map = new HashMap<>();
        jdbc.query("SELECT location_id, visit_days FROM kiosk_count_visit_config", rs -> {
            map.put(rs.getLong("location_id"), rs.getInt("visit_days"));
        });
        return map;
    }

    /** Quién de contabilidad liquidó por última vez cada kiosco (para proponerlo de nuevo). */
    private Map<Long, String> loadLastAccountants() {
        Map<Long, String> map = new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (location_id) location_id, accountant FROM kiosk_liquidation "
                + "WHERE accountant IS NOT NULL AND TRIM(accountant) <> '' ORDER BY location_id, period_to DESC", rs -> {
            map.put(rs.getLong("location_id"), rs.getString("accountant"));
        });
        return map;
    }

    static LocalDate weekStart(LocalDate day) {
        LocalDate d = day == null ? GuatemalaDateTime.today() : day;
        return d.minusDays(d.getDayOfWeek().getValue() - (long) DayOfWeek.MONDAY.getValue());
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static String blankToNull(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().replaceAll("\\s+", " ");
        return t.substring(0, Math.min(max, t.length()));
    }

    private static String displayName(String name) {
        return name == null ? "" : name.replaceFirst("(?i)^CUEROGLAM\\s+", "").trim();
    }
}
