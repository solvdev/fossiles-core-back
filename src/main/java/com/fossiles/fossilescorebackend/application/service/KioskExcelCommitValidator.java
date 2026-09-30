package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExcelCommitRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Revalida (sin confiar en el cliente) el payload del commit de importación y lo deja normalizado
 * (resoluciones aplicadas, sitios destino identificados). Puro: los accesos a datos se inyectan.
 */
public class KioskExcelCommitValidator {

    private static final BigDecimal MAX_AMOUNT = KioskExcelParser.MAX_AMOUNT;
    private static final int MAX_PROBLEMS_IN_MESSAGE = 15;

    /** Destino de una columna del Excel. */
    public static class ColumnTarget {
        /** Sitio existente (alias o mapeo manual); null si se crea. */
        public Long siteId;
        /** Datos del sitio histórico a crear; null si {@code siteId != null}. */
        public KioskExcelCommitRequest.CreateSite create;
        /** true si el sitio viene de un mapeo manual (se debe registrar alias nuevo). */
        public boolean manual;
    }

    public static class ResolvedFile {
        public String fileName;
        public String sha256;
        public int year;
        public int month;
        public final Map<String, ColumnTarget> targets = new LinkedHashMap<>();
        /** columna -> fecha -> monto (sólo celdas con dato, resoluciones ya aplicadas). */
        public final Map<String, TreeMap<LocalDate, BigDecimal>> sales = new LinkedHashMap<>();
        public final Map<String, BigDecimal> goals = new LinkedHashMap<>();
        public final Map<String, KioskExcelDataDto.Rates> rates = new LinkedHashMap<>();
        public final Map<String, Map<String, BigDecimal>> costs = new LinkedHashMap<>();
    }

    public static class Result {
        public final List<String> problems = new ArrayList<>();
        public final List<ResolvedFile> files = new ArrayList<>();

        public boolean isValid() {
            return problems.isEmpty();
        }

        public void throwIfInvalid() throws BusinessException {
            if (problems.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("La importación fue rechazada (")
                    .append(problems.size()).append(problems.size() == 1 ? " problema" : " problemas").append("): ");
            int shown = Math.min(problems.size(), MAX_PROBLEMS_IN_MESSAGE);
            for (int i = 0; i < shown; i++) {
                sb.append(i == 0 ? "" : " | ").append(problems.get(i));
            }
            if (problems.size() > shown) {
                sb.append(" | ... y ").append(problems.size() - shown).append(" más");
            }
            throw new BusinessException(sb.toString());
        }
    }

    private final Function<String, Long> aliasLookup;
    private final Predicate<Long> siteExists;
    private final Set<String> knownCostCodes;

    /**
     * @param aliasLookup   alias normalizado -> id de sitio (o null)
     * @param siteExists    true si el id de sitio existe
     * @param knownCostCodes códigos válidos de kiosk_cost_category
     */
    public KioskExcelCommitValidator(Function<String, Long> aliasLookup, Predicate<Long> siteExists,
                                     Set<String> knownCostCodes) {
        this.aliasLookup = aliasLookup;
        this.siteExists = siteExists;
        this.knownCostCodes = knownCostCodes;
    }

    public Result validate(KioskExcelCommitRequest request) {
        Result result = new Result();
        if (request == null || request.getFiles() == null || request.getFiles().isEmpty()) {
            result.problems.add("No se recibieron archivos para importar.");
            return result;
        }
        if (request.getFiles().size() > 24) {
            result.problems.add("Demasiados archivos en una sola importación (máximo 24).");
            return result;
        }
        Set<String> seenPeriods = new HashSet<>();
        for (KioskExcelCommitRequest.FileCommit file : request.getFiles()) {
            String label = "Archivo '" + (file == null ? "?" : file.getFileName()) + "'";
            if (file == null) {
                result.problems.add("Archivo vacío en la lista.");
                continue;
            }
            ResolvedFile resolved = validateFile(file, label, result.problems);
            if (resolved != null) {
                if (!seenPeriods.add(resolved.year + "-" + resolved.month)) {
                    result.problems.add(label + ": hay más de un archivo para " + resolved.year + "-"
                            + String.format("%02d", resolved.month) + ".");
                }
                result.files.add(resolved);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------

    private ResolvedFile validateFile(KioskExcelCommitRequest.FileCommit file, String label, List<String> problems) {
        int before = problems.size();
        if (isBlank(file.getFileName())) {
            problems.add("Un archivo no trae nombre.");
        }
        String sha = file.getSha256() == null ? "" : file.getSha256().trim().toLowerCase();
        if (!sha.matches("[0-9a-f]{64}")) {
            problems.add(label + ": sha256 inválido.");
        }
        Integer year = file.getYear();
        Integer month = file.getMonth();
        if (year == null || year < 2000 || year > 2100) {
            problems.add(label + ": año inválido (" + year + ").");
        }
        if (month == null || month < 1 || month > 12) {
            problems.add(label + ": mes inválido (" + month + ").");
        }
        KioskExcelDataDto data = file.getData();
        if (data == null || data.getDays() == null) {
            problems.add(label + ": faltan los datos parseados.");
        }
        if (problems.size() > before) {
            return null;
        }

        ResolvedFile out = new ResolvedFile();
        out.fileName = file.getFileName();
        out.sha256 = sha;
        out.year = year;
        out.month = month;
        YearMonth ym = YearMonth.of(year, month);

        // ---- columnas presentes en los datos
        Set<String> columns = new java.util.LinkedHashSet<>();
        Set<LocalDate> seenDates = new HashSet<>();
        if (data.getDays().size() > 31) {
            problems.add(label + ": más de 31 días en el mes.");
        }
        for (KioskExcelDataDto.Day day : data.getDays()) {
            if (day == null || day.getDate() == null) {
                problems.add(label + ": una fila de día no trae fecha.");
                continue;
            }
            if (!YearMonth.from(day.getDate()).equals(ym)) {
                problems.add(label + ": la fecha " + day.getDate() + " no pertenece a " + ym + ".");
            }
            if (!seenDates.add(day.getDate())) {
                problems.add(label + ": fecha repetida " + day.getDate() + ".");
            }
            if (day.getValues() != null) {
                columns.addAll(day.getValues().keySet());
            }
        }
        addKeys(columns, data.getGoals());
        addKeys(columns, data.getRates());
        addKeys(columns, data.getCosts());
        for (String c : columns) {
            if (isBlank(c)) {
                problems.add(label + ": hay una columna sin nombre.");
            }
        }
        columns.removeIf(KioskExcelCommitValidator::isBlank);

        // ---- destino de cada columna
        Map<Long, String> siteToColumn = new HashMap<>();
        Map<String, String> createdNames = new HashMap<>();
        for (String col : columns) {
            KioskExcelCommitRequest.SiteMappingEntry mapping = lookupMapping(file.getSiteMapping(), col);
            ColumnTarget target = new ColumnTarget();
            if (mapping != null && mapping.getSiteId() != null) {
                if (!siteExists.test(mapping.getSiteId())) {
                    problems.add(label + ": el sitio " + mapping.getSiteId() + " asignado a '" + col + "' no existe.");
                    continue;
                }
                target.siteId = mapping.getSiteId();
                target.manual = true;
            } else if (mapping != null && mapping.getCreate() != null) {
                String name = mapping.getCreate().getName() == null ? "" : mapping.getCreate().getName().trim();
                if (name.isEmpty() || name.length() > 120) {
                    problems.add(label + ": nombre de sitio nuevo inválido para la columna '" + col + "'.");
                    continue;
                }
                String status = mapping.getCreate().getStatus() == null ? "CLOSED"
                        : mapping.getCreate().getStatus().trim().toUpperCase();
                if (!status.equals("ACTIVE") && !status.equals("CLOSED")) {
                    problems.add(label + ": estado de sitio inválido para '" + name + "' (ACTIVE | CLOSED).");
                    continue;
                }
                String key = KioskExcelParser.normalizeAlias(name);
                String other = createdNames.put(key, col);
                if (other != null) {
                    problems.add(label + ": las columnas '" + other + "' y '" + col + "' crean el mismo sitio '" + name + "'.");
                    continue;
                }
                target.create = KioskExcelCommitRequest.CreateSite.builder()
                        .name(name).status(status).closedOn(mapping.getCreate().getClosedOn()).build();
            } else {
                Long site = aliasLookup.apply(KioskExcelParser.normalizeAlias(col));
                if (site == null) {
                    problems.add(label + ": la columna '" + col
                            + "' no tiene sitio: asígnela a un sitio existente o cree un sitio histórico.");
                    continue;
                }
                target.siteId = site;
            }
            if (target.siteId != null) {
                String prev = siteToColumn.put(target.siteId, col);
                if (prev != null) {
                    problems.add(label + ": las columnas '" + prev + "' y '" + col + "' apuntan al mismo sitio (" + target.siteId + ").");
                    continue;
                }
            }
            out.targets.put(col, target);
        }

        // ---- resoluciones de celdas bloqueadas
        Map<String, BigDecimal> numericResolutions = new HashMap<>();
        Set<String> ignored = new HashSet<>();
        Map<String, Object> resolutions = file.getResolutions() == null ? Map.of() : file.getResolutions();
        Set<String> blockedIds = new HashSet<>();
        Map<String, KioskExcelDataDto.BlockedCell> blockedByCell = new HashMap<>();
        if (data.getBlockedCells() != null) {
            for (KioskExcelDataDto.BlockedCell b : data.getBlockedCells()) {
                if (b == null || b.getIssueId() == null) {
                    continue;
                }
                blockedIds.add(b.getIssueId());
                blockedByCell.put(cellKey(b.getExcelName(), b.getDate()), b);
            }
        }
        for (Map.Entry<String, Object> e : resolutions.entrySet()) {
            if (!blockedIds.contains(e.getKey())) {
                problems.add(label + ": la resolución '" + e.getKey() + "' no corresponde a ninguna celda bloqueada.");
                continue;
            }
            Object v = e.getValue();
            if (v instanceof String s && s.trim().equalsIgnoreCase("IGNORE")) {
                ignored.add(e.getKey());
                continue;
            }
            BigDecimal number = toDecimal(v);
            if (number == null) {
                problems.add(label + ": la resolución '" + e.getKey() + "' debe ser un número o \"IGNORE\".");
            } else if (number.signum() < 0 || number.compareTo(MAX_AMOUNT) >= 0) {
                problems.add(label + ": la resolución '" + e.getKey() + "' (" + number.toPlainString()
                        + ") debe estar entre 0 y 999,999.99.");
            } else {
                numericResolutions.put(e.getKey(), number);
            }
        }
        if (data.getBlockedCells() != null) {
            for (KioskExcelDataDto.BlockedCell b : data.getBlockedCells()) {
                if (b == null || b.getIssueId() == null) {
                    continue;
                }
                if (!numericResolutions.containsKey(b.getIssueId()) && !ignored.contains(b.getIssueId())
                        && !resolutions.containsKey(b.getIssueId())) {
                    problems.add(label + ": falta resolver la celda " + describe(b) + ".");
                }
            }
        }

        // ---- ventas
        for (String col : out.targets.keySet()) {
            out.sales.put(col, new TreeMap<>());
        }
        for (KioskExcelDataDto.Day day : data.getDays()) {
            if (day == null || day.getDate() == null || day.getValues() == null) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : day.getValues().entrySet()) {
                String col = e.getKey();
                if (!out.targets.containsKey(col)) {
                    continue;
                }
                BigDecimal value = e.getValue();
                KioskExcelDataDto.BlockedCell blocked = blockedByCell.get(cellKey(col, day.getDate()));
                if (blocked != null && blocked.getIssueId() != null) {
                    String id = blocked.getIssueId();
                    if (ignored.contains(id)) {
                        value = null;
                    } else if (numericResolutions.containsKey(id)) {
                        value = numericResolutions.get(id);
                    }
                }
                if (value == null) {
                    continue;
                }
                if (value.signum() < 0) {
                    problems.add(label + ": venta negativa en '" + col + "' el " + day.getDate() + " (" + value.toPlainString() + ").");
                    continue;
                }
                if (value.compareTo(MAX_AMOUNT) >= 0) {
                    problems.add(label + ": venta de '" + col + "' el " + day.getDate() + " supera Q999,999.99 (" + value.toPlainString() + ").");
                    continue;
                }
                out.sales.get(col).put(day.getDate(), value);
            }
        }

        // resoluciones numéricas cuya celda no venía como clave en el mapa de valores
        if (data.getBlockedCells() != null) {
            for (KioskExcelDataDto.BlockedCell b : data.getBlockedCells()) {
                if (b == null || b.getIssueId() == null || b.getDate() == null || b.getExcelName() == null) {
                    continue;
                }
                BigDecimal number = numericResolutions.get(b.getIssueId());
                if (number != null && out.sales.containsKey(b.getExcelName())
                        && YearMonth.from(b.getDate()).equals(ym)) {
                    out.sales.get(b.getExcelName()).put(b.getDate(), number);
                }
            }
        }

        // ---- metas, tasas, costos
        for (String col : out.targets.keySet()) {
            BigDecimal goal = data.getGoals() == null ? null : data.getGoals().get(col);
            if (goal != null) {
                if (goal.signum() < 0 || goal.compareTo(MAX_AMOUNT) >= 0) {
                    problems.add(label + ": meta fuera de rango en '" + col + "' (" + goal.toPlainString() + ").");
                } else {
                    out.goals.put(col, goal);
                }
            }
            KioskExcelDataDto.Rates rates = data.getRates() == null ? null : data.getRates().get(col);
            if (rates != null) {
                checkRate(label, col, "costo del producto", rates.getProductCostPct(), problems);
                checkRate(label, col, "comisión de venta", rates.getSalesCommissionPct(), problems);
                checkRate(label, col, "comisión de tarjeta", rates.getCardCommissionPct(), problems);
                checkRate(label, col, "IVA", rates.getTaxPct(), problems);
                out.rates.put(col, rates);
            }
            Map<String, BigDecimal> costs = data.getCosts() == null ? null : data.getCosts().get(col);
            if (costs != null) {
                Map<String, BigDecimal> clean = new LinkedHashMap<>();
                for (Map.Entry<String, BigDecimal> e : costs.entrySet()) {
                    if (e.getKey() == null || !knownCostCodes.contains(e.getKey())) {
                        problems.add(label + ": categoría de costo desconocida '" + e.getKey() + "' en '" + col + "'.");
                        continue;
                    }
                    BigDecimal amount = e.getValue();
                    if (amount == null) {
                        continue;
                    }
                    if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) >= 0) {
                        problems.add(label + ": costo " + e.getKey() + " fuera de rango en '" + col + "' (" + amount.toPlainString() + ").");
                        continue;
                    }
                    clean.put(e.getKey(), amount.setScale(2, RoundingMode.HALF_UP));
                }
                out.costs.put(col, clean);
            }
        }
        return out;
    }

    private static void checkRate(String label, String col, String what, BigDecimal value, List<String> problems) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            problems.add(label + ": tasa de " + what + " fuera de 0..1 en '" + col + "' (" + value.toPlainString() + ").");
        }
    }

    private static KioskExcelCommitRequest.SiteMappingEntry lookupMapping(
            Map<String, KioskExcelCommitRequest.SiteMappingEntry> mapping, String column) {
        if (mapping == null) {
            return null;
        }
        KioskExcelCommitRequest.SiteMappingEntry entry = mapping.get(column);
        if (entry != null) {
            return entry;
        }
        String normalized = KioskExcelParser.normalizeAlias(column);
        for (Map.Entry<String, KioskExcelCommitRequest.SiteMappingEntry> e : mapping.entrySet()) {
            if (normalized.equals(KioskExcelParser.normalizeAlias(e.getKey()))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static void addKeys(Set<String> target, Map<String, ?> map) {
        if (map != null) {
            target.addAll(map.keySet());
        }
    }

    private static String cellKey(String column, LocalDate date) {
        return column + "|" + date;
    }

    private static String describe(KioskExcelDataDto.BlockedCell b) {
        return (b.getCell() != null ? b.getCell() + " " : "") + "('" + b.getExcelName() + "' el " + b.getDate()
                + ", valor '" + b.getRawValue() + "')";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof BigDecimal bd) {
                return bd;
            }
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;
                }
                return new BigDecimal(n.toString());
            }
            if (v instanceof String s) {
                return new BigDecimal(s.trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
