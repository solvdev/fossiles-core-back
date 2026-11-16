package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.DateConflict;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportPreview;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.UnmatchedKiosk;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga el Excel «Liquidaciones por semana» de contabilidad: por cada fila deja el conteo hecho (el «al» del período)
 * en {@code kiosk_count_history}, que el generador usa como último conteo, y la liquidación semanal en
 * {@code kiosk_liquidation}. Primero se muestra una vista previa; el Excel manda sobre lo que ya tenga el sistema.
 */
@Service
@RequiredArgsConstructor
public class KioskCountImportService {

    /** Nombres del Excel que no coinciden con el nombre del kiosco en el sistema (ya sin tildes ni símbolos). */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("MIRAFLORES 2", "MIRAF2"),
            Map.entry("COBAN", "MAGDACOBAN"),
            Map.entry("CEJALES QUICHE", "CELAJES"),
            Map.entry("CELAJES QUICHE", "CELAJES"),
            Map.entry("VILLA LOBOS", "INT_VLOBOS"),
            Map.entry("SANKRIS MALL", "SANKRIS"),
            Map.entry("SANTAL", "SANTALU"),
            Map.entry("NARANJO", "NARANJO"),
            Map.entry("ENTRECUEROS", "ETRC"));

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("NINGUNA", 0), Map.entry("NINGUN", 0), Map.entry("NO", 0), Map.entry("SIN", 0),
            Map.entry("UNA", 1), Map.entry("UN", 1), Map.entry("DOS", 2), Map.entry("TRES", 3), Map.entry("TREAS", 3),
            Map.entry("CUATRO", 4), Map.entry("CINCO", 5), Map.entry("SEIS", 6), Map.entry("SIETE", 7),
            Map.entry("OCHO", 8), Map.entry("NUEVE", 9), Map.entry("DIEZ", 10));

    private static final Pattern DIGITS = Pattern.compile("^(\\d{1,2})\\b");

    private final JdbcTemplate jdbc;
    private final KioskCountCalendarAccessGuard guard;

    private record Loc(long id, String code, String name) {
    }

    private record Match(ImportRow row, Loc loc) {
    }

    // ───────────────────────── vista previa y carga ─────────────────────────

    @Transactional(readOnly = true)
    public ImportPreview preview(List<ImportRow> rows) throws BusinessException {
        guard.assertCanGenerate();
        return analyze(rows).preview;
    }

    @Transactional
    public ImportResult apply(List<ImportRow> rows) throws BusinessException {
        Long userId = guard.assertCanGenerate();
        Analysis analysis = analyze(rows);
        LocalDate today = GuatemalaDateTime.today();
        Map<Long, Long> supervisorByLocation = loadSupervisorByLocation();
        Map<String, Long> supervisorByLastName = loadSupervisorLastNames();
        int history = 0;
        int liquidations = 0;
        for (Match m : analysis.matches) {
            ImportRow r = m.row;
            Long supervisorId = supervisorByLocation.get(m.loc.id);
            if (supervisorId == null) {
                supervisorId = supervisorFromSheet(r.sheetSupervisor(), supervisorByLastName);
            }
            jdbc.update("INSERT INTO kiosk_count_history (location_id, supervisor_user_id, period_from, period_to, source, imported_by) "
                            + "VALUES (?, ?, ?, ?, 'EXCEL', ?) ON CONFLICT (location_id, period_to) DO UPDATE SET "
                            + "period_from = EXCLUDED.period_from, supervisor_user_id = EXCLUDED.supervisor_user_id, "
                            + "source = 'EXCEL', imported_by = EXCLUDED.imported_by, imported_at = NOW()",
                    m.loc.id, supervisorId, sqlDate(r.periodFrom()), Date.valueOf(r.periodTo()), userId);
            history++;
            List<LocalDate> days = liquidationDays(r);
            if (!days.isEmpty() && supervisorId != null) {
                LocalDate first = days.get(0);
                String status = !first.isAfter(today) ? "LIQUIDATED" : "PENDING";
                jdbc.update("INSERT INTO kiosk_liquidation (location_id, supervisor_user_id, period_from, period_to, accountant, "
                                + "corrections_due, liquidation_date, liquidation_days, areas_to_fix, green_note, status, source, created_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IMPORT', ?) "
                                + "ON CONFLICT (location_id, period_to) DO UPDATE SET supervisor_user_id = EXCLUDED.supervisor_user_id, "
                                + "period_from = EXCLUDED.period_from, accountant = EXCLUDED.accountant, "
                                + "corrections_due = EXCLUDED.corrections_due, liquidation_date = EXCLUDED.liquidation_date, "
                                + "liquidation_days = EXCLUDED.liquidation_days, areas_to_fix = EXCLUDED.areas_to_fix, "
                                + "green_note = EXCLUDED.green_note, status = EXCLUDED.status, source = 'IMPORT', updated_at = NOW()",
                        m.loc.id, supervisorId, sqlDate(r.periodFrom()), Date.valueOf(r.periodTo()), clean(r.accountant(), 80),
                        sqlDate(r.correctionsDue()), Date.valueOf(first), Math.min(5, days.size()),
                        parseAreasToFix(r.greenNote()), clean(r.greenNote(), 300), status, userId);
                liquidations++;
            }
        }
        return new ImportResult(history, liquidations, analysis.preview);
    }

    // ───────────────────────── análisis ─────────────────────────

    private record Analysis(List<Match> matches, ImportPreview preview) {
    }

    private Analysis analyze(List<ImportRow> rows) throws BusinessException {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("El archivo no tiene filas para cargar.");
        }
        Map<String, Loc> byName = new HashMap<>();
        Map<String, Loc> byCode = new HashMap<>();
        jdbc.query("SELECT id, code, name FROM locations WHERE categoria = 'KIOSKO'", rs -> {
            Loc loc = new Loc(rs.getLong("id"), rs.getString("code"), displayName(rs.getString("name")));
            byName.put(normalize(loc.name), loc);
            byCode.put(loc.code, loc);
        });

        Map<String, Integer> unmatched = new TreeMap<>();
        Map<String, Match> unique = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int duplicates = 0;
        int invalid = 0;
        for (ImportRow r : rows) {
            if (r.kioskName() == null || r.periodTo() == null) {
                invalid++;
                continue;
            }
            Loc loc = matchKiosk(r.kioskName(), byName, byCode);
            if (loc == null) {
                unmatched.merge(r.kioskName().trim(), 1, Integer::sum);
                continue;
            }
            if (r.periodFrom() != null && r.periodFrom().isAfter(r.periodTo())) {
                warnings.add("Fila " + r.sourceRow() + " (" + loc.name + "): el período empieza después de terminar.");
            }
            for (LocalDate d : liquidationDays(r)) {
                if (d.isBefore(r.periodTo())) {
                    warnings.add("Fila " + r.sourceRow() + " (" + loc.name + "): la liquidación (" + d
                            + ") es anterior al conteo (" + r.periodTo() + "). Revisa el año en el Excel.");
                    break;
                }
            }
            String key = loc.id + "|" + r.periodTo();
            if (unique.put(key, new Match(r, loc)) != null) {
                duplicates++;
            }
        }
        if (invalid > 0) {
            warnings.add(invalid + " fila(s) sin kiosco o sin fecha del conteo no se cargan.");
        }
        if (duplicates > 0) {
            warnings.add(duplicates + " fila(s) repetidas (mismo kiosco y misma fecha de conteo): se queda la última.");
        }

        List<Match> matches = new ArrayList<>(unique.values());
        Map<Long, List<LocalDate>> systemCounts = new HashMap<>();
        jdbc.query("SELECT location_id, period_to FROM kiosco_physical_count WHERE status <> 'DRAFT'", rs -> {
            systemCounts.computeIfAbsent(rs.getLong("location_id"), id -> new ArrayList<>())
                    .add(rs.getDate("period_to").toLocalDate());
        });
        Set<String> existingHistory = new java.util.HashSet<>();
        jdbc.query("SELECT location_id, period_to FROM kiosk_count_history", rs -> {
            existingHistory.add(rs.getLong("location_id") + "|" + rs.getDate("period_to").toLocalDate());
        });

        List<DateConflict> conflicts = new ArrayList<>();
        int already = 0;
        int withLiquidation = 0;
        LocalDate first = null;
        LocalDate last = null;
        Set<Long> kiosks = new java.util.HashSet<>();
        for (Match m : matches) {
            kiosks.add(m.loc.id);
            LocalDate to = m.row.periodTo();
            first = first == null || to.isBefore(first) ? to : first;
            last = last == null || to.isAfter(last) ? to : last;
            if (!liquidationDays(m.row).isEmpty()) {
                withLiquidation++;
            }
            if (existingHistory.contains(m.loc.id + "|" + to)) {
                already++;
            }
            List<LocalDate> sys = systemCounts.getOrDefault(m.loc.id, List.of());
            if (sys.stream().noneMatch(d -> d.equals(to))) {
                sys.stream().filter(d -> Math.abs(ChronoUnit.DAYS.between(d, to)) <= 10)
                        .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(d, to))))
                        .ifPresent(d -> conflicts.add(new DateConflict(m.loc.name, to, d)));
            }
        }
        List<UnmatchedKiosk> unmatchedList = unmatched.entrySet().stream()
                .map(e -> new UnmatchedKiosk(e.getKey(), e.getValue())).toList();
        ImportPreview preview = new ImportPreview(rows.size(), matches.size(), kiosks.size(), first, last, withLiquidation,
                already, unmatchedList, conflicts, warnings);
        return new Analysis(matches, preview);
    }

    // ───────────────────────── utilidades (también usadas en pruebas) ─────────────────────────

    private Loc matchKiosk(String sheetName, Map<String, Loc> byName, Map<String, Loc> byCode) {
        String key = normalize(sheetName);
        if (key.isEmpty()) {
            return null;
        }
        String code = ALIASES.get(key);
        if (code != null) {
            return byCode.get(code);
        }
        Loc exact = byName.get(key);
        if (exact != null) {
            return exact;
        }
        // «MIRAFLORES II» ↔ «MIRAFLORES 2», «PERI-ROOSEVELT» ↔ «PERI ROOSEVELT»: probar variantes romanas
        return byName.get(key.replaceAll("\\b2\\b", "II"));
    }

    /** Mayúsculas, sin tildes ni símbolos (también los caracteres rotos del Excel). */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String s = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toUpperCase(Locale.ROOT).replace('�', ' ');
        return s.replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /** «UNA AREA PARA CORREGIR» → 1, «5 AREAS…» → 5, «NINGUNA / NO TIENE / SIN AREAS…» → 0; null si no se entiende. */
    static Integer parseAreasToFix(String text) {
        String s = normalize(text);
        if (s.isEmpty()) {
            return null;
        }
        Matcher digits = DIGITS.matcher(s);
        if (digits.find()) {
            return Integer.parseInt(digits.group(1));
        }
        String first = s.split(" ")[0];
        if (NUMBER_WORDS.containsKey(first)) {
            return NUMBER_WORDS.get(first);
        }
        return s.contains("AREA") && (s.contains("NINGUNA") || s.contains("NO TIENE")) ? 0 : null;
    }

    private static List<LocalDate> liquidationDays(ImportRow r) {
        if (r.liquidationDates() == null) {
            return List.of();
        }
        return r.liquidationDates().stream().filter(d -> d != null).sorted().distinct().toList();
    }

    private Map<Long, Long> loadSupervisorByLocation() {
        Map<Long, Long> map = new HashMap<>();
        jdbc.query("SELECT kiosk_location_id, MIN(supervisor_user_id) AS sup FROM kiosk_supervisor_assignment GROUP BY kiosk_location_id",
                rs -> {
                    map.put(rs.getLong("kiosk_location_id"), rs.getLong("sup"));
                });
        return map;
    }

    private Map<String, Long> loadSupervisorLastNames() {
        Map<String, Long> map = new HashMap<>();
        jdbc.query("SELECT DISTINCT u.id, u.last_name FROM users u JOIN kiosk_supervisor_assignment a ON a.supervisor_user_id = u.id",
                rs -> {
                    String last = normalize(rs.getString("last_name"));
                    if (!last.isEmpty()) {
                        map.put(last, rs.getLong("id"));
                    }
                });
        return map;
    }

    /** El Excel pone «ANA ISABEL CON» / «GABRIELA MORALES»: se reconoce por el apellido de la supervisora. */
    private static Long supervisorFromSheet(String sheetSupervisor, Map<String, Long> byLastName) {
        String text = " " + normalize(sheetSupervisor) + " ";
        for (Map.Entry<String, Long> e : byLastName.entrySet()) {
            if (text.contains(" " + e.getKey() + " ")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static String clean(String text, int max) {
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
