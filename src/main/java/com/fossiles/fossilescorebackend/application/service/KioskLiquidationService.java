package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.CalendarResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.VisitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.HistoryRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationGenerateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationGroup;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationWeekResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationYearResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.NonWorkingDay;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.PendingCount;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.Person;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Liquidación semanal de los conteos: la revisión de contabilidad de cada conteo físico (si salió bien el conteo,
 * depósitos, cambios, facturas verdes), unas 4 semanas después de la visita. Se arma por semana, un kiosco por día,
 * como en el Excel «Liquidaciones por semana».
 * <p>
 * El estado y quién revisó salen del <b>conteo físico</b> (única fuente): Contado → Revisado → Cerrado. Un conteo cerrado
 * es una liquidación hecha. Las liquidaciones nacen de las visitas hechas del calendario. Quien revisa es un usuario con
 * rol CONTABILIDAD, un revisor adicional ({@code kiosk_liquidation_extra_reviewer}) o quien ya revisa/cierra conteos.
 */
@Service
@RequiredArgsConstructor
public class KioskLiquidationService {

    private static final int CORRECTIONS_DAYS_AFTER_COUNT = 2;
    /** Un conteo que se registra a mano a menos de estos días de uno que ya tiene el sistema es el mismo conteo. */
    private static final int SAME_COUNT_TOLERANCE_DAYS = 3;

    /** Usuarios que pueden revisar liquidaciones: rol CONTABILIDAD o revisor adicional. */
    static final String REVIEWER_FILTER = "(UPPER(r.name) = 'CONTABILIDAD' OR EXISTS ("
            + "SELECT 1 FROM kiosk_liquidation_extra_reviewer x WHERE x.user_id = u.id) OR EXISTS ("
            + "SELECT 1 FROM kiosco_physical_count pc WHERE pc.reviewed_by = u.id OR pc.closed_by = u.id))";

    private final JdbcTemplate jdbc;
    private final KioskCountCalendarAccessGuard guard;
    private final KioskCountCalendarService calendarService;

    public record UpdateRequest(String status, Integer areasToFix, Boolean clearAreas, List<Long> accountantUserIds,
                                LocalDate correctionsDue, LocalDate liquidationDate, Integer liquidationDays,
                                String greenNote, String note) {
    }

    public record ManualRequest(Long locationId, LocalDate countDate, LocalDate liquidationDate, Integer liquidationDays,
                                List<Long> accountantUserIds, String note) {
    }

    // ───────────────────────── consulta ─────────────────────────

    @Transactional(readOnly = true)
    public LiquidationWeekResponse getWeek(LocalDate anyDay, Long supervisorUserId) throws BusinessException {
        guard.assertCanView();
        return buildWeek(weekStart(anyDay), supervisorUserId);
    }

    /** Todas las liquidaciones de un año, por supervisora, para el Excel «Liquidaciones por semana». */
    @Transactional(readOnly = true)
    public LiquidationYearResponse getYear(Integer year) throws BusinessException {
        guard.assertCanView();
        int y = year == null ? GuatemalaDateTime.today().getYear() : year;
        if (y < 2000 || y > 2100) {
            throw new BusinessException("Año no válido.");
        }
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to = LocalDate.of(y, 12, 31);
        Map<Long, String> names = loadSupervisorNames();
        Map<Long, List<LiquidationRow>> byWho = new LinkedHashMap<>();
        names.keySet().stream()
                .sorted(Comparator.comparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER))
                .forEach(id -> byWho.put(id, new ArrayList<>()));
        for (LiquidationRow row : loadRows(from, to, null)) {
            byWho.computeIfAbsent(row.supervisorUserId(), id -> new ArrayList<>()).add(row);
        }
        addCalendarVisits(y, byWho);
        List<LiquidationGroup> groups = new ArrayList<>();
        byWho.forEach((id, rows) -> groups.add(new LiquidationGroup(id, names.getOrDefault(id, "Supervisora"), rows)));
        List<NonWorkingDay> nonWorking = new ArrayList<>();
        calendarService.nonWorkingBetween(from, to).forEach((d, reason) -> nonWorking.add(new NonWorkingDay(d, reason)));
        return new LiquidationYearResponse(y, groups, nonWorking);
    }

    /**
     * Agrega al año las visitas del calendario aprobado (esta semana incluida) que todavía no tienen una liquidación
     * registrada del mismo kiosco en esa semana.
     */
    private void addCalendarVisits(int year, Map<Long, List<LiquidationRow>> byWho) throws BusinessException {
        Set<String> registered = byWho.values().stream().flatMap(List::stream)
                .map(r -> r.locationId() + "|" + weekStart(r.liquidationDate()))
                .collect(Collectors.toSet());
        List<String> months = jdbc.queryForList("SELECT DISTINCT visit_month FROM kiosk_count_visit "
                + "WHERE status NOT IN ('CANCELLED', 'DRAFT') AND visit_month LIKE ? ORDER BY visit_month",
                String.class, year + "-%");
        for (String month : months) {
            for (LiquidationGroup group : getVisitSheet(month).groups()) {
                for (LiquidationRow row : group.rows()) {
                    // La hoja de un mes trae semanas completas: cada visita se toma solo en su mes.
                    if (month.equals(YearMonth.from(row.liquidationDate()).toString())
                            && !registered.contains(row.locationId() + "|" + weekStart(row.liquidationDate()))) {
                        byWho.computeIfAbsent(row.supervisorUserId(), id -> new ArrayList<>()).add(row);
                    }
                }
            }
        }
        byWho.values().forEach(rows -> rows.sort(Comparator.comparing(LiquidationRow::liquidationDate)));
    }

    /**
     * Las visitas del calendario de un mes con las columnas del Excel de contabilidad: cada fila es una visita
     * (fecha de liquidación). Trae el conteo hecho en esa visita; mientras no se haya contado, el inventario
     * anterior del kiosco.
     */
    @Transactional(readOnly = true)
    public LiquidationYearResponse getVisitSheet(String month) throws BusinessException {
        CalendarResponse calendar = calendarService.getCalendar(month, null);
        List<VisitResponse> visits = calendar.visits().stream().filter(v -> !"DRAFT".equals(v.status())).toList();
        YearMonth view = YearMonth.parse(calendar.month());
        if (visits.isEmpty()) {
            return new LiquidationYearResponse(view.getYear(), List.of(), List.of());
        }
        List<LocalDate> previous = visits.stream().map(KioskLiquidationService::inventoryDate)
                .filter(d -> d != null).toList();
        LocalDate from = previous.stream().min(Comparator.naturalOrder()).orElse(view.atDay(1))
                .minusDays(SAME_COUNT_TOLERANCE_DAYS);
        LocalDate to = view.atEndOfMonth().plusDays(7);
        List<InventoryData> counts = loadSystemInventories(from, to);
        List<InventoryData> liquidations = loadLiquidationInventories(from, to);

        // Una visita de varios días (Entrecueros, Celajes) es una sola fila.
        Map<Long, List<VisitResponse>> units = new LinkedHashMap<>();
        Map<Long, VisitResponse> lastOfUnit = new HashMap<>();
        Map<Long, Long> unitOfLocation = new HashMap<>();
        for (VisitResponse v : visits.stream().sorted(Comparator.comparing(VisitResponse::visitDate)).toList()) {
            Long unit = unitOfLocation.get(v.locationId());
            if (v.daySeq() > 1 && unit != null
                    && ChronoUnit.DAYS.between(lastOfUnit.get(unit).visitDate(), v.visitDate()) <= 3) {
                units.get(unit).add(v);
            } else {
                unit = v.id();
                units.put(unit, new ArrayList<>(List.of(v)));
                unitOfLocation.put(v.locationId(), unit);
            }
            lastOfUnit.put(unit, v);
        }

        Map<Long, String> names = loadSupervisorNames();
        Map<Long, List<LiquidationRow>> byWho = new LinkedHashMap<>();
        names.keySet().stream()
                .sorted(Comparator.comparing(id -> names.get(id), String.CASE_INSENSITIVE_ORDER))
                .forEach(id -> byWho.put(id, new ArrayList<>()));
        for (List<VisitResponse> unit : units.values()) {
            VisitResponse v = unit.get(0);
            LocalDate periodTo = inventoryDate(v);
            InventoryData count = nearest(counts, v.locationId(), periodTo);
            InventoryData liq = nearest(liquidations, v.locationId(), periodTo);
            LocalDate periodFrom = count != null && count.periodFrom() != null ? count.periodFrom()
                    : liq == null ? null : liq.periodFrom();
            LocalDate corrections = liq != null && liq.correctionsDue() != null ? liq.correctionsDue()
                    : count == null ? null : count.correctionsDue();
            byWho.computeIfAbsent(v.supervisorUserId(), id -> new ArrayList<>()).add(new LiquidationRow(v.id(),
                    v.locationId(), v.locationName(), v.supervisorUserId(), v.supervisorName(), periodFrom,
                    periodTo, liq == null ? null : liq.accountant(), corrections,
                    v.actualDate() == null ? v.visitDate() : v.actualDate(), unit.size(),
                    liq == null ? null : liq.areasToFix(), liq == null ? null : liq.greenNote(), "PENDING", v.note(),
                    v.kind(), List.of(), null, null, count == null ? null : count.reviewedBy(), null,
                    count == null ? null : count.closedBy(), null, null, count == null ? null : count.notes()));
        }
        List<LiquidationGroup> groups = new ArrayList<>();
        byWho.forEach((id, rows) -> groups.add(new LiquidationGroup(id, names.getOrDefault(id, "Supervisora"), rows)));
        List<NonWorkingDay> nonWorking = calendar.nonWorkingDays().stream()
                .map(d -> new NonWorkingDay(d.date(), d.reason())).toList();
        return new LiquidationYearResponse(view.getYear(), groups, nonWorking);
    }

    private static LocalDate inventoryDate(VisitResponse visit) {
        return visit.countedOn() != null ? visit.countedOn() : visit.lastCountDate();
    }

    /** Lo que se sabe de un inventario, venga del conteo físico o de su liquidación. */
    private record InventoryData(long locationId, LocalDate periodFrom, LocalDate periodTo, String accountant,
                                 LocalDate correctionsDue, Integer areasToFix, String greenNote, String reviewedBy,
                                 String closedBy, String notes) {
    }

    private static InventoryData nearest(List<InventoryData> list, long locationId, LocalDate periodTo) {
        if (periodTo == null) {
            return null;
        }
        return list.stream()
                .filter(d -> d.locationId() == locationId
                        && Math.abs(ChronoUnit.DAYS.between(d.periodTo(), periodTo)) <= SAME_COUNT_TOLERANCE_DAYS)
                .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(d.periodTo(), periodTo))))
                .orElse(null);
    }

    /** Conteos físicos (sin borradores): quién revisó y cerró, y cuándo se entregó la revisión. */
    private List<InventoryData> loadSystemInventories(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT c.location_id, c.period_from, c.period_to, c.notes, COALESCE(c.reviewed_at, c.closed_at) AS done_at, "
                        + "ru.first_name AS rf, ru.last_name AS rl, ru.username AS ru_user, cu.first_name AS cf, "
                        + "cu.last_name AS cl, cu.username AS cu_user FROM kiosco_physical_count c "
                        + "LEFT JOIN users ru ON ru.id = c.reviewed_by LEFT JOIN users cu ON cu.id = c.closed_by "
                        + "WHERE c.status <> 'DRAFT' AND c.period_to BETWEEN ? AND ? ORDER BY c.id DESC",
                (rs, i) -> {
                    java.sql.Timestamp doneAt = rs.getTimestamp("done_at");
                    return new InventoryData(rs.getLong("location_id"), localDate(rs.getDate("period_from")),
                            rs.getDate("period_to").toLocalDate(), null,
                            doneAt == null ? null : doneAt.toLocalDateTime().toLocalDate(), null, null,
                            rs.getString("ru_user") == null ? null
                                    : fullName(rs.getString("rf"), rs.getString("rl"), rs.getString("ru_user")),
                            rs.getString("cu_user") == null ? null
                                    : fullName(rs.getString("cf"), rs.getString("cl"), rs.getString("cu_user")),
                            rs.getString("notes"));
                }, Date.valueOf(from), Date.valueOf(to));
    }

    /** Liquidaciones (con contabilidad asignada y facturas verdes) y el historial del Excel (solo el período). */
    private List<InventoryData> loadLiquidationInventories(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT q.location_id, q.period_from, q.period_to, q.accountant, q.corrections_due, "
                        + "q.areas_to_fix, q.green_note, 0 AS pr FROM kiosk_liquidation q WHERE q.period_to BETWEEN ? AND ? "
                        + "UNION ALL SELECT h.location_id, h.period_from, h.period_to, NULL, NULL, NULL, NULL, 1 "
                        + "FROM kiosk_count_history h WHERE h.period_to BETWEEN ? AND ? ORDER BY pr",
                (rs, i) -> new InventoryData(rs.getLong("location_id"), localDate(rs.getDate("period_from")),
                        rs.getDate("period_to").toLocalDate(), rs.getString("accountant"),
                        localDate(rs.getDate("corrections_due")), (Integer) rs.getObject("areas_to_fix"),
                        rs.getString("green_note"), null, null, null),
                Date.valueOf(from), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to));
    }

    // ───────────────────────── historial de conteos cerrados ─────────────────────────

    /**
     * Todos los conteos cerrados. Los del sistema son los de estado CERRADO; los anteriores salen de las liquidaciones
     * cargadas del Excel que no tienen conteo en el sistema (si el sistema lo tiene, manda su estado).
     */
    @Transactional(readOnly = true)
    public List<HistoryRow> getClosedHistory() throws BusinessException {
        guard.assertCanView();
        String near = " <= " + SAME_COUNT_TOLERANCE_DAYS + " ";
        String sql = "SELECT t.*, l.name, u.first_name, u.last_name, u.username, "
                + "COALESCE(cfg.visits_per_month, 1) AS per_month FROM ("
                // Conteos cerrados del sistema, con su liquidación si la hay.
                + "SELECT c.location_id, COALESCE(c.period_from, q.period_from) AS period_from, c.period_to, "
                + "q.accountant, ru.first_name AS reviewer, cu.first_name AS closer, "
                + "COALESCE(CAST(c.reviewed_at AS date), CAST(c.closed_at AS date), q.corrections_due) AS delivery, "
                + "COALESCE(q.liquidation_date, CAST(c.closed_at AS date)) AS liquidation, "
                + "COALESCE(NULLIF(btrim(c.notes), ''), q.green_note) AS notes, q.areas_to_fix, "
                + "COALESCE(q.supervisor_user_id, (SELECT MIN(a.supervisor_user_id) FROM kiosk_supervisor_assignment a "
                + "WHERE a.kiosk_location_id = c.location_id)) AS supervisor_id "
                + "FROM kiosco_physical_count c "
                + "LEFT JOIN LATERAL (SELECT x.* FROM kiosk_liquidation x WHERE x.location_id = c.location_id "
                + "AND ABS(x.period_to - c.period_to)" + near
                + "ORDER BY ABS(x.period_to - c.period_to), x.id DESC LIMIT 1) q ON TRUE "
                + "LEFT JOIN users ru ON ru.id = c.reviewed_by LEFT JOIN users cu ON cu.id = c.closed_by "
                + "WHERE c.status = 'CERRADO' "
                + "UNION ALL "
                // Historial anterior al sistema.
                + "SELECT q.location_id, q.period_from, q.period_to, q.accountant, NULL, NULL, q.corrections_due, "
                + "q.liquidation_date, q.green_note, q.areas_to_fix, q.supervisor_user_id "
                + "FROM kiosk_liquidation q WHERE q.source = 'IMPORT' AND q.liquidation_date <= ? "
                + "AND NOT EXISTS (SELECT 1 FROM kiosco_physical_count c WHERE c.location_id = q.location_id "
                + "AND ABS(c.period_to - q.period_to)" + near + ")"
                + ") t JOIN locations l ON l.id = t.location_id "
                + "LEFT JOIN users u ON u.id = t.supervisor_id "
                + "LEFT JOIN kiosk_count_visit_config cfg ON cfg.location_id = t.location_id "
                + "ORDER BY t.period_to, l.name";
        return jdbc.query(sql, (rs, i) -> {
            String accountant = rs.getString("accountant");
            if (accountant == null || accountant.isBlank()) {
                accountant = rs.getString("reviewer") != null ? rs.getString("reviewer") : rs.getString("closer");
            }
            Long supervisorId = rs.getObject("supervisor_id") == null ? null : rs.getLong("supervisor_id");
            String supervisor = supervisorId == null ? "Sin supervisora"
                    : fullName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("username"));
            return new HistoryRow(supervisorId, supervisor, rs.getLong("location_id"),
                    displayName(rs.getString("name")), localDate(rs.getDate("period_from")),
                    rs.getDate("period_to").toLocalDate(), accountant, localDate(rs.getDate("delivery")),
                    localDate(rs.getDate("liquidation")), rs.getString("notes"),
                    (Integer) rs.getObject("areas_to_fix"), rs.getInt("per_month") > 1);
        }, Date.valueOf(GuatemalaDateTime.today()));
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
        Map<Long, List<Long>> lastAccountants = loadLastAccountants();

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
                insertLiquidation(s.locationId(), supervisorId, s.periodTo(), s.date(), s.days(),
                        lastAccountants.getOrDefault(s.locationId(), List.of()), null, "GENERATED", userId);
            }
            int left = pending.size() - slots.size();
            boolean full = weekDays.stream().allMatch(occupied::contains);
            if (slots.isEmpty() && full) {
                warnings.add(name + ": la semana ya tiene liquidación todos sus días.");
            } else if (slots.isEmpty()) {
                warnings.add(name + ": no hay conteos por liquidar que quepan en esa semana"
                        + (pending.isEmpty() ? " (no hay conteos hechos del calendario por liquidar)." : " (un conteo se puede liquidar desde el día siguiente)."));
            } else if (left > 0) {
                warnings.add(name + ": quedan " + left + " conteo(s) por liquidar para las siguientes semanas.");
            }
        }
        return new LiquidationGenerateResponse(buildWeek(weekStart, null), warnings);
    }

    // ───────────────────────── fijar a mano ─────────────────────────

    /**
     * Fija a mano la fecha de liquidación de un conteo y, si hace falta, registra el conteo. El sistema manda: si el
     * kiosco ya tiene un conteo (del sistema o del Excel) con esa fecha o a pocos días, se usa el de él. Sin fecha de
     * liquidación solo se registra el conteo, que queda esperando su turno.
     */
    @Transactional
    public LiquidationGenerateResponse fixManually(ManualRequest req, LocalDate viewDay) throws BusinessException {
        Long userId = guard.assertCanGenerate();
        if (req.locationId() == null || req.countDate() == null) {
            throw new BusinessException("Indica el kiosco y la fecha del conteo.");
        }
        LocalDate today = GuatemalaDateTime.today();
        if (req.countDate().isAfter(today)) {
            throw new BusinessException("La fecha del conteo no puede ser futura.");
        }
        List<Map<String, Object>> sup = jdbc.queryForList(
                "SELECT MIN(supervisor_user_id) AS sup FROM kiosk_supervisor_assignment WHERE kiosk_location_id = ?",
                req.locationId());
        Object supObj = sup.isEmpty() ? null : sup.get(0).get("sup");
        if (supObj == null) {
            throw new BusinessException("Ese kiosco no tiene supervisora asignada.");
        }
        long supervisorId = ((Number) supObj).longValue();
        List<String> warnings = new ArrayList<>();

        // El sistema manda: ¿ya existe ese conteo?
        LocalDate periodTo = req.countDate();
        List<LocalDate> fromSystem = jdbc.query(
                "SELECT period_to FROM kiosco_physical_count WHERE location_id = ? AND status <> 'DRAFT'",
                (rs, i) -> rs.getDate("period_to").toLocalDate(), req.locationId());
        List<LocalDate> known = new ArrayList<>(fromSystem);
        known.addAll(jdbc.query("SELECT period_to FROM kiosk_count_history WHERE location_id = ?",
                (rs, i) -> rs.getDate("period_to").toLocalDate(), req.locationId()));
        LocalDate nearest = known.stream()
                .filter(d -> Math.abs(ChronoUnit.DAYS.between(d, req.countDate())) <= SAME_COUNT_TOLERANCE_DAYS)
                .min(Comparator.comparingLong((LocalDate d) -> Math.abs(ChronoUnit.DAYS.between(d, req.countDate())))
                        .thenComparing(d -> fromSystem.contains(d) ? 0 : 1))
                .orElse(null);
        if (nearest != null) {
            periodTo = nearest;
            if (!nearest.equals(req.countDate())) {
                warnings.add("El sistema ya tiene este conteo el " + nearest + ": se usó esa fecha (el sistema manda).");
            }
        } else {
            jdbc.update("INSERT INTO kiosk_count_history (location_id, supervisor_user_id, period_to, source, imported_by) "
                    + "VALUES (?, ?, ?, 'MANUAL', ?) ON CONFLICT (location_id, period_to) DO NOTHING",
                    req.locationId(), supervisorId, Date.valueOf(periodTo), userId);
        }

        LocalDate view = viewDay != null ? viewDay : req.liquidationDate() != null ? req.liquidationDate() : periodTo;
        if (req.liquidationDate() == null) {
            warnings.add("Conteo registrado: queda esperando su fecha de liquidación.");
            return new LiquidationGenerateResponse(buildWeek(weekStart(view), null), warnings);
        }
        int days = req.liquidationDays() != null ? req.liquidationDays() : loadVisitDays().getOrDefault(req.locationId(), 1);
        if (days < 1 || days > 5) {
            throw new BusinessException("Los días de liquidación deben estar entre 1 y 5.");
        }
        List<Long> accountants = req.accountantUserIds() != null ? req.accountantUserIds()
                : loadLastAccountants().getOrDefault(req.locationId(), List.of());
        assertReviewers(accountants);

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM kiosk_liquidation WHERE location_id = ? AND period_to = ?", req.locationId(), Date.valueOf(periodTo));
        if (existing.isEmpty()) {
            insertLiquidation(req.locationId(), supervisorId, periodTo, req.liquidationDate(), days, accountants,
                    req.note(), "MANUAL", userId);
        } else {
            long id = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("UPDATE kiosk_liquidation SET liquidation_date = ?, liquidation_days = ?, note = COALESCE(?, note), "
                            + "updated_at = NOW() WHERE id = ?",
                    Date.valueOf(req.liquidationDate()), days, blankToNull(req.note(), 500), id);
            if (req.accountantUserIds() != null) {
                setAccountants(id, accountants);
            }
            warnings.add("Ese conteo ya tenía liquidación: se cambió su fecha.");
        }
        return new LiquidationGenerateResponse(buildWeek(weekStart(viewDay != null ? viewDay : req.liquidationDate()), null), warnings);
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
        if (req.status() != null && hasLinkedCount(id)) {
            throw new BusinessException("El estado lo define el conteo físico: la liquidación queda hecha cuando contabilidad cierra el conteo.");
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
        if (req.accountantUserIds() != null) {
            assertReviewers(req.accountantUserIds());
            setAccountants(id, req.accountantUserIds());
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

    private boolean hasLinkedCount(long liquidationId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kiosk_liquidation q " + COUNT_LATERAL
                + "WHERE q.id = ? AND c.id IS NOT NULL", Integer.class, liquidationId);
        return n != null && n > 0;
    }

    // ───────────────────────── quién de contabilidad revisa ─────────────────────────

    /** Usuarios activos que pueden revisar: rol CONTABILIDAD y revisores adicionales (Roberto Lique). */
    private List<Person> loadReviewers() {
        return jdbc.query("SELECT DISTINCT u.id, u.first_name, u.last_name, u.username FROM users u "
                        + "LEFT JOIN user_role ur ON ur.user_id = u.id LEFT JOIN role r ON r.id = ur.role_id "
                        + "WHERE u.status = 'active' AND " + REVIEWER_FILTER + " ORDER BY u.first_name, u.id",
                (rs, i) -> new Person(rs.getLong("id"), fullName(rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("username"))));
    }

    private void assertReviewers(List<Long> ids) throws BusinessException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> allowed = loadReviewers().stream().map(Person::userId).collect(Collectors.toSet());
        for (Long id : ids) {
            if (!allowed.contains(id)) {
                throw new BusinessException("Solo se puede asignar a usuarios activos de contabilidad.");
            }
        }
    }

    private void setAccountants(long liquidationId, List<Long> userIds) {
        jdbc.update("DELETE FROM kiosk_liquidation_accountant WHERE liquidation_id = ?", liquidationId);
        List<Long> distinct = userIds == null ? List.of() : userIds.stream().distinct().toList();
        for (Long userId : distinct) {
            jdbc.update("INSERT INTO kiosk_liquidation_accountant (liquidation_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    liquidationId, userId);
        }
        Map<Long, Person> people = new HashMap<>();
        loadReviewers().forEach(p -> people.put(p.userId(), p));
        String text = distinct.stream().map(people::get).filter(p -> p != null).map(Person::name)
                .collect(Collectors.joining(" y "));
        jdbc.update("UPDATE kiosk_liquidation SET accountant = ?, updated_at = NOW() WHERE id = ?",
                blankToNull(text, 80), liquidationId);
    }

    private void insertLiquidation(long locationId, long supervisorId, LocalDate periodTo, LocalDate date, int days,
                                   List<Long> accountants, String note, String source, Long userId) {
        List<Long> ids = jdbc.query("INSERT INTO kiosk_liquidation (location_id, supervisor_user_id, period_from, period_to, "
                        + "corrections_due, liquidation_date, liquidation_days, status, source, note, created_by) "
                        + "VALUES (?, ?, (SELECT MIN(x.period_from) FROM (SELECT location_id, period_to, period_from FROM kiosco_physical_count "
                        + "UNION ALL SELECT location_id, period_to, period_from FROM kiosk_count_history) x "
                        + "WHERE x.location_id = ? AND x.period_to = ?), ?, ?, ?, ?, 'PENDING', ?, ?, ?) "
                        + "ON CONFLICT (location_id, period_to) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong("id"),
                locationId, supervisorId, locationId, Date.valueOf(periodTo), Date.valueOf(periodTo),
                Date.valueOf(periodTo.plusDays(CORRECTIONS_DAYS_AFTER_COUNT)), Date.valueOf(date), days, source,
                blankToNull(note, 500), userId);
        if (!ids.isEmpty() && accountants != null && !accountants.isEmpty()) {
            setAccountants(ids.get(0), accountants);
        }
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

        boolean canGenerate;
        try {
            guard.assertCanGenerate();
            canGenerate = true;
        } catch (BusinessException e) {
            canGenerate = false;
        }
        return new LiquidationWeekResponse(weekStart, weekStart.plusDays(6), groups, pending, loadReviewers(), canGenerate);
    }

    /** Conteo físico de una liquidación: mismo kiosco y misma fecha (o a ≤ 3 días); el más cercano, y no borrador si hay. */
    private static final String COUNT_LATERAL = "LEFT JOIN LATERAL (SELECT c1.* FROM kiosco_physical_count c1 "
            + "WHERE c1.location_id = q.location_id AND ABS(c1.period_to - q.period_to) <= " + SAME_COUNT_TOLERANCE_DAYS + " "
            + "ORDER BY ABS(c1.period_to - q.period_to), CASE WHEN c1.status = 'DRAFT' THEN 1 ELSE 0 END, c1.id DESC LIMIT 1) c ON TRUE "
            + "LEFT JOIN users ru ON ru.id = c.reviewed_by LEFT JOIN users cu ON cu.id = c.closed_by ";

    private List<LiquidationRow> loadRows(LocalDate from, LocalDate to, Long supervisorId) {
        String sql = "SELECT q.id, q.location_id, l.name, q.supervisor_user_id, q.period_from, q.period_to, q.accountant, "
                + "q.corrections_due, q.liquidation_date, q.liquidation_days, q.areas_to_fix, q.green_note, q.status, q.note, q.source, "
                + "c.id AS count_id, c.status AS count_status, c.reviewed_at, c.closed_at, c.observations AS count_obs, c.notes AS count_notes, "
                + "ru.first_name AS rf, ru.last_name AS rl, ru.username AS ru_user, cu.first_name AS cf, cu.last_name AS cl, cu.username AS cu_user "
                + "FROM kiosk_liquidation q JOIN locations l ON l.id = q.location_id " + COUNT_LATERAL
                + "WHERE q.liquidation_date BETWEEN ? AND ? " + (supervisorId == null ? "" : "AND q.supervisor_user_id = ? ")
                + "ORDER BY q.liquidation_date, q.id";
        Object[] args = supervisorId == null ? new Object[]{Date.valueOf(from), Date.valueOf(to)}
                : new Object[]{Date.valueOf(from), Date.valueOf(to), supervisorId};
        Map<Long, String> names = loadSupervisorNames();
        List<LiquidationRow> rows = jdbc.query(sql, (rs, i) -> {
            String countStatus = rs.getString("count_status");
            // Con conteo enlazado, el estado lo define el conteo: cerrado = liquidada.
            String status = countStatus == null ? rs.getString("status") : "CERRADO".equals(countStatus) ? "LIQUIDATED" : "PENDING";
            java.sql.Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
            java.sql.Timestamp closedAt = rs.getTimestamp("closed_at");
            return new LiquidationRow(rs.getLong("id"), rs.getLong("location_id"),
                    displayName(rs.getString("name")), rs.getLong("supervisor_user_id"),
                    names.getOrDefault(rs.getLong("supervisor_user_id"), ""),
                    localDate(rs.getDate("period_from")), rs.getDate("period_to").toLocalDate(), rs.getString("accountant"),
                    localDate(rs.getDate("corrections_due")), rs.getDate("liquidation_date").toLocalDate(),
                    rs.getInt("liquidation_days"), (Integer) rs.getObject("areas_to_fix"), rs.getString("green_note"),
                    status, rs.getString("note"), rs.getString("source"), List.of(),
                    rs.getObject("count_id") == null ? null : rs.getLong("count_id"), countStatus,
                    rs.getObject("count_id") == null || rs.getObject("rf") == null && rs.getObject("ru_user") == null ? null
                            : fullName(rs.getString("rf"), rs.getString("rl"), rs.getString("ru_user")),
                    reviewedAt == null ? null : reviewedAt.toLocalDateTime().toLocalDate(),
                    rs.getObject("cf") == null && rs.getObject("cu_user") == null ? null
                            : fullName(rs.getString("cf"), rs.getString("cl"), rs.getString("cu_user")),
                    closedAt == null ? null : closedAt.toLocalDateTime().toLocalDate(), rs.getString("count_obs"),
                    rs.getString("count_notes"));
        }, args);
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, List<Person>> people = new HashMap<>();
        jdbc.query("SELECT la.liquidation_id, u.id, u.first_name, u.last_name, u.username FROM kiosk_liquidation_accountant la "
                        + "JOIN users u ON u.id = la.user_id JOIN kiosk_liquidation q ON q.id = la.liquidation_id "
                        + "WHERE q.liquidation_date BETWEEN ? AND ? ORDER BY u.first_name",
                rs -> {
                    people.computeIfAbsent(rs.getLong("liquidation_id"), k -> new ArrayList<>()).add(new Person(rs.getLong("id"),
                            fullName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("username"))));
                }, Date.valueOf(from), Date.valueOf(to));
        List<LiquidationRow> out = new ArrayList<>();
        for (LiquidationRow r : rows) {
            out.add(new LiquidationRow(r.id(), r.locationId(), r.locationName(), r.supervisorUserId(), r.supervisorName(),
                    r.periodFrom(), r.periodTo(), r.accountant(), r.correctionsDue(), r.liquidationDate(),
                    r.liquidationDays(), r.areasToFix(), r.greenNote(), r.status(), r.note(), r.source(),
                    people.getOrDefault(r.id(), List.of()), r.countId(), r.countStatus(), r.reviewedBy(), r.reviewedAt(),
                    r.closedBy(), r.closedAt(), r.countObservations(), r.countNotes()));
        }
        return out;
    }

    /**
     * Conteos de una supervisora que todavía no tienen fecha de liquidación. Nacen de las visitas del calendario:
     * conteos del sistema que corresponden a una visita (SISTEMA), visitas marcadas como hechas que aún no tienen
     * conteo (CALENDARIO) y conteos registrados a mano (MANUAL). El historial del Excel no genera liquidaciones.
     */
    private List<PendingCount> loadPending(Long supervisorId) {
        return jdbc.query("SELECT c.location_id, l.name, c.period_to, c.src FROM ("
                        + "SELECT DISTINCT ON (location_id, period_to) location_id, period_to, src FROM ("
                        + "SELECT pc.location_id, pc.period_to, 'SISTEMA' AS src, 0 AS pr FROM kiosco_physical_count pc "
                        + "WHERE pc.status <> 'DRAFT' AND EXISTS (SELECT 1 FROM kiosk_count_visit v WHERE v.location_id = pc.location_id "
                        + "AND v.status NOT IN ('CANCELLED', 'DRAFT') AND ABS(v.visit_date - pc.period_to) <= " + KioskCountVisitMatcher.MATCH_DAYS + ") "
                        + "UNION ALL SELECT v.location_id, v.visit_date, 'CALENDARIO', 1 FROM kiosk_count_visit v "
                        + "WHERE v.status = 'DONE' AND v.day_seq = 1 AND NOT EXISTS (SELECT 1 FROM kiosco_physical_count pc "
                        + "WHERE pc.location_id = v.location_id AND pc.status <> 'DRAFT' AND ABS(pc.period_to - v.visit_date) <= " + KioskCountVisitMatcher.MATCH_DAYS + ") "
                        + "UNION ALL SELECT h.location_id, h.period_to, 'MANUAL', 2 FROM kiosk_count_history h WHERE h.source = 'MANUAL') u "
                        + "ORDER BY location_id, period_to, pr) c "
                        + "JOIN locations l ON l.id = c.location_id "
                        + "JOIN kiosk_supervisor_assignment a ON a.kiosk_location_id = c.location_id AND a.supervisor_user_id = ? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM kiosk_liquidation q WHERE q.location_id = c.location_id "
                        + "AND ABS(q.period_to - c.period_to) <= " + SAME_COUNT_TOLERANCE_DAYS + ") "
                        + "ORDER BY c.period_to",
                (rs, i) -> new PendingCount(rs.getLong("location_id"), displayName(rs.getString("name")), supervisorId,
                        rs.getDate("period_to").toLocalDate(), rs.getString("src")), supervisorId);
    }

    private Map<Long, String> loadSupervisorNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT u.id, u.first_name, u.last_name, u.username FROM users u "
                + "JOIN kiosk_supervisor_assignment a ON a.supervisor_user_id = u.id", rs -> {
            names.put(rs.getLong("id"), fullName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("username")));
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
    private Map<Long, List<Long>> loadLastAccountants() {
        Map<Long, List<Long>> map = new HashMap<>();
        jdbc.query("SELECT q.location_id, la.user_id FROM kiosk_liquidation_accountant la "
                + "JOIN kiosk_liquidation q ON q.id = la.liquidation_id "
                + "JOIN users u ON u.id = la.user_id AND u.status = 'active' "
                + "WHERE q.id IN (SELECT DISTINCT ON (q2.location_id) q2.id FROM kiosk_liquidation q2 "
                + "JOIN kiosk_liquidation_accountant la2 ON la2.liquidation_id = q2.id ORDER BY q2.location_id, q2.period_to DESC)", rs -> {
            map.computeIfAbsent(rs.getLong("location_id"), k -> new ArrayList<>()).add(rs.getLong("user_id"));
        });
        return map;
    }

    static LocalDate weekStart(LocalDate day) {
        LocalDate d = day == null ? GuatemalaDateTime.today() : day;
        return d.minusDays(d.getDayOfWeek().getValue() - (long) DayOfWeek.MONDAY.getValue());
    }

    private static String fullName(String first, String last, String username) {
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return full.isEmpty() ? username : full;
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
