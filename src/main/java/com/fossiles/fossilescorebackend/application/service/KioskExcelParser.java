package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskSupervisionCost;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Lector (puro y sin estado) de los Excel mensuales "VENTAS &lt;MES&gt; 2025.xlsx" de Cueroglam.
 * El layout no es fijo: se detectan anclas por etiqueta normalizada (ver docs/KIOSK-FINANCIALS-CONTRACT.md).
 * No accede a base de datos: el mapeo de columnas a sitios lo hace {@link KioskExcelImportService}.
 */
@Component
public class KioskExcelParser {

    /** Categorías de costo fijo: prefijo de la etiqueta normalizada (en el orden del Excel) -> código. */
    static final Map<String, String> FIXED_COST_PREFIXES = new LinkedHashMap<>();
    static {
        FIXED_COST_PREFIXES.put("ALQUILER", "ALQUILER");
        FIXED_COST_PREFIXES.put("LUZ", "LUZ");
        FIXED_COST_PREFIXES.put("TELEFONO", "TELEFONO_INTERNET_PROG");
        FIXED_COST_PREFIXES.put("MANTENIMIENTO", "MANTENIMIENTO");
        FIXED_COST_PREFIXES.put("SALARIOS MO INDIRECTA", "SALARIOS_MO_INDIRECTA");
        FIXED_COST_PREFIXES.put("BONIFICACI", "BONIFICACION");
        FIXED_COST_PREFIXES.put("INDEMNIZACION", "INDEMNIZACION_VACACIONES");
        FIXED_COST_PREFIXES.put("BONO 14", "BONO_14");
        FIXED_COST_PREFIXES.put("AGUINALDO", "AGUINALDO");
        FIXED_COST_PREFIXES.put("SALARIOS MO DIRECTA", "SALARIOS_MO_DIRECTA");
        // Formato 2026 (hojas "ventas 20XX"): mismas categorías con otra etiqueta. "Salarios encargadas" ocupa el
        // lugar de "Salarios MO indirecta" y "Salarios suplentes" el de "Salarios MO directa" (mismos montos que
        // en los Excel anteriores: 4002.28 y 500), aunque sus etiquetas digan MOD/MOI.
        FIXED_COST_PREFIXES.put("SALARIOS ENCARGADAS", "SALARIOS_MO_INDIRECTA");
        FIXED_COST_PREFIXES.put("SALARIOS SUPLENTES", "SALARIOS_MO_DIRECTA");
        FIXED_COST_PREFIXES.put("SUPERVISION", "SUPERVISION");
    }

    /** Código de la categoría de supervisión (sólo existe en el formato 2026; se calcula con {@link KioskSupervisionCost}). */
    public static final String SUPERVISION = "SUPERVISION";
    /** Categorías que no se exigen para considerar un mes completo (los Excel anteriores a 2026 no la traen). */
    public static final Set<String> OPTIONAL_COST_CODES = Set.of(SUPERVISION);

    public static final List<String> COST_CODES = FIXED_COST_PREFIXES.values().stream().distinct().toList();

    public static final String FORMAT_LEGACY = "LEGACY";
    public static final String FORMAT_SHEET_YEAR = "SHEET_YEAR";
    public static final String PERIOD_FROM_DATES = "DATES";
    public static final String PERIOD_FROM_FILE_NAME = "FILE_NAME";
    public static final String PERIOD_OVERRIDE = "OVERRIDE";

    public static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.01");
    public static final BigDecimal OUTLIER_MIN_AMOUNT = new BigDecimal("3000");
    public static final BigDecimal OUTLIER_MEDIAN_FACTOR = new BigDecimal("6");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private static final int HEADER_SEARCH_ROWS = 30;
    private static final int MAX_DAY_ROWS = 31;
    private static final Map<String, Integer> MONTHS = new HashMap<>();
    static {
        String[] names = {"ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO",
                "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"};
        for (int i = 0; i < names.length; i++) {
            MONTHS.put(names[i], i + 1);
        }
        MONTHS.put("SETIEMBRE", 9);
    }
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "\\b(ENERO|FEBRERO|MARZO|ABRIL|MAYO|JUNIO|JULIO|AGOSTO|SEPTIEMBRE|SETIEMBRE|OCTUBRE|NOVIEMBRE|DICIEMBRE)\\b");
    private static final Pattern YEAR_ONLY = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern SHEET_YEAR_NAME = Pattern.compile("^VENTAS\\b.*?(20\\d{2})$");
    private static final Pattern PERCENT_IN_LABEL = Pattern.compile("\\((\\d+(?:[.,]\\d+)?)\\s*%\\)");
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(ENERO|FEBRERO|MARZO|ABRIL|MAYO|JUNIO|JULIO|AGOSTO|SEPTIEMBRE|SETIEMBRE|OCTUBRE|NOVIEMBRE|DICIEMBRE)\\D{0,10}?(20\\d{2})");

    // ------------------------------------------------------------------ resultado

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedColumn {
        private String excelName;
        private String normalized;
        /** Índice de columna (0-based) en la hoja. */
        private int columnIndex;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseResult {
        private String fileName;
        private String sheetName;
        private int year;
        private int month;
        private List<ParsedColumn> columns;
        private List<KioskExcelIssueDto> issues;
        private KioskExcelDataDto data;
        /** Filas de fecha dentro del mes. */
        private int days;
        /** Celdas de venta numéricas (no vacías) dentro del mes. */
        private int salesCells;
        /** Suma recalculada por kiosco (incluye valores sugeridos de celdas de texto reparables). */
        private Map<String, BigDecimal> recomputedTotals;
        /** Fila "Total" de la hoja por kiosco (sólo si la celda tiene valor). */
        private Map<String, BigDecimal> sheetTotals;
        /** {@link #FORMAT_LEGACY} (hoja "Reporte de Vtas  orig.") o {@link #FORMAT_SHEET_YEAR} (hojas "ventas 20XX"). */
        private String format;
        /** De dónde salió el mes: DATES | FILE_NAME | OVERRIDE. */
        private String periodSource;
        /** true si el mes/año puede corregirse desde el asistente (formato 2026: las fechas de la hoja no son fiables). */
        private boolean periodEditable;
    }

    // ------------------------------------------------------------------ API

    public ParseResult parse(String fileName, byte[] content) throws BusinessException {
        return parse(fileName, content, null);
    }

    /**
     * @param periodOverride mes/año indicado por el usuario; sólo se aplica al formato 2026, donde las fechas de la
     *                       hoja pueden venir con el mes o el año equivocado. El formato anterior lo ignora.
     */
    public ParseResult parse(String fileName, byte[] content, YearMonth periodOverride) throws BusinessException {
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo '" + fileName + "' está vacío.");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet legacy = findLegacySheet(workbook);
            if (legacy != null) {
                return parseSheet(fileName, legacy);
            }
            YearSheet yearSheet = findYearSheet(workbook);
            if (yearSheet != null) {
                return parseYearSheet(fileName, yearSheet, periodOverride);
            }
            Sheet sheet = pickSheet(workbook);
            if (sheet == null) {
                throw new BusinessException("El archivo '" + fileName + "' no contiene hojas.");
            }
            return parseSheet(fileName, sheet); // lanza el aviso de encabezado no encontrado
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("No se pudo leer '" + fileName + "' como Excel (.xlsx): " + e.getMessage(), e);
        }
    }

    /** NFD, sin marcas diacríticas, MAYÚSCULAS, sólo A-Z 0-9 y espacio (elimina U+FFFD), espacios colapsados, trim. */
    public static String normalizeAlias(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace(' ', ' ').replace('\t', ' ');
        n = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "");
        return n.replaceAll(" +", " ").trim();
    }

    /**
     * Intenta reparar un texto numérico sucio (p. ej. "1254..6" -> 1254.6): quita espacios y símbolo de moneda,
     * colapsa puntos/comas repetidos y sólo devuelve un valor si es un número plausible (0 &lt;= v &lt; 1,000,000).
     */
    public static BigDecimal repairNumericText(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(' ', ' ').replaceAll("\\s+", "");
        s = s.replaceFirst("^(?i)(GTQ|Q|\\$)", "");
        if (s.isEmpty() || !s.matches("[0-9.,]+")) {
            return null;
        }
        s = s.replaceAll("\\.{2,}", ".").replaceAll(",{2,}", ",");
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastDot > lastComma) {
                s = s.replace(",", "");
            } else {
                s = s.replace(".", "").replace(',', '.');
            }
        } else if (lastComma >= 0) {
            if (s.matches("\\d{1,3}(,\\d{3})+")) {
                s = s.replace(",", "");
            } else if (s.indexOf(',') == lastComma) {
                s = s.replace(',', '.');
            } else {
                return null;
            }
        } else if (lastDot >= 0 && s.indexOf('.') != lastDot) {
            if (s.matches("\\d{1,3}(\\.\\d{3})+")) {
                s = s.replace(".", "");
            } else {
                return null;
            }
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.equals(".")) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(s);
            if (value.signum() < 0 || value.compareTo(MAX_AMOUNT) >= 0) {
                return null;
            }
            return clean(value, 2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ parseo

    private Sheet pickSheet(Workbook workbook) {
        int n = workbook.getNumberOfSheets();
        for (int i = 0; i < n; i++) {
            Sheet s = workbook.getSheetAt(i);
            if (!"HOJA1".equals(normalizeAlias(s.getSheetName()))) {
                return s;
            }
        }
        return n > 0 ? workbook.getSheetAt(0) : null;
    }

    private ParseResult parseSheet(String fileName, Sheet sheet) throws BusinessException {
        List<KioskExcelIssueDto> issues = new ArrayList<>();

        // 1) Encabezado: fila con "Fecha" (col A..C) y una celda "Total por d..." a su derecha
        int[] located = findLegacyHeader(sheet);
        int headerRow = located == null ? -1 : located[0];
        int labelCol = located == null ? -1 : located[1];
        if (headerRow < 0) {
            throw new BusinessException("No se encontró la fila de encabezado (\"Fecha\" ... \"Total por día\") en la hoja '"
                    + sheet.getSheetName() + "' de '" + fileName + "'.");
        }
        Row header = sheet.getRow(headerRow);

        // 2) Columnas de kiosco: desde labelCol+1 hasta "Total por d..." (se ignoran vacías intercaladas)
        List<ParsedColumn> columns = new ArrayList<>();
        Map<String, String> seenNormalized = new HashMap<>();
        int lastHeaderCell = Math.max(header.getLastCellNum(), labelCol + 1);
        int totalCol = -1;
        for (int c = labelCol + 1; c < lastHeaderCell; c++) {
            String raw = headerName(header.getCell(c));
            if (raw.isEmpty()) {
                continue;
            }
            String norm = normalizeAlias(raw);
            if (norm.startsWith("TOTAL POR D")) {
                totalCol = c;
                break;
            }
            if (seenNormalized.containsKey(norm)) {
                addIssue(issues, KioskExcelIssueDto.BLOCKING, KioskExcelIssueDto.DUPLICATE_COLUMN,
                        "La columna '" + raw + "' está repetida en el encabezado (ya existe '" + seenNormalized.get(norm)
                                + "'); sólo se lee la primera.", raw, null, ref(headerRow, c), raw, null);
                continue;
            }
            seenNormalized.put(norm, raw);
            columns.add(ParsedColumn.builder().excelName(raw).normalized(norm).columnIndex(c).build());
        }
        if (columns.isEmpty()) {
            throw new BusinessException("No se encontraron columnas de kioscos en '" + fileName + "'.");
        }

        // 3) Filas de fecha: entre el encabezado y la fila "Total"
        int totalRow = -1;
        for (int r = headerRow + 1; r <= headerRow + MAX_DAY_ROWS + 3; r++) {
            if ("TOTAL".equals(normalizeAlias(textOf(cellAt(sheet, r, labelCol))))) {
                totalRow = r;
                break;
            }
        }
        int lastDayRow;
        if (totalRow < 0) {
            lastDayRow = headerRow + MAX_DAY_ROWS;
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontró la fila 'Total'; se asumen " + MAX_DAY_ROWS + " filas de fecha tras el encabezado.",
                    null, null, null, null, null);
        } else {
            lastDayRow = totalRow - 1;
        }

        Map<Integer, LocalDate> rowDates = new TreeMap<>();
        Map<YearMonth, Integer> monthVotes = new HashMap<>();
        LocalDate previous = null;
        boolean inferredDates = false;
        for (int r = headerRow + 1; r <= lastDayRow; r++) {
            LocalDate d = dateOf(cellAt(sheet, r, labelCol));
            if (d == null && previous != null) {
                d = previous.plusDays(1);
                inferredDates = true;
            }
            if (d != null) {
                rowDates.put(r, d);
                monthVotes.merge(YearMonth.from(d), 1, Integer::sum);
                previous = d;
            }
        }
        if (inferredDates) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "Alguna celda de fecha no se pudo leer; se infirió como el día siguiente al anterior.",
                    null, null, null, null, null);
        }

        // 4) Año/mes: mayoría de la columna de fechas; respaldo: nombre de archivo / título de la hoja
        YearMonth hint = monthFromText(fileName);
        if (hint == null) {
            hint = monthFromTitle(sheet);
        }
        YearMonth ym = null;
        int best = 0;
        for (Map.Entry<YearMonth, Integer> e : monthVotes.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                ym = e.getKey();
            }
        }
        if (ym == null) {
            if (hint == null) {
                throw new BusinessException("No se pudo determinar el mes/año de '" + fileName
                        + "' (sin fechas legibles ni mes en el nombre o título).");
            }
            ym = hint;
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "Mes/año tomado del nombre de archivo o título (" + ym + "): la columna de fechas no es legible.",
                    null, null, null, null, null);
        } else if (hint != null && !hint.equals(ym)) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "El nombre/título del archivo indica " + hint + " pero las fechas indican " + ym
                            + "; se usa el mes de las fechas.", null, null, null, null, null);
        }

        // 5) Filas etiquetadas (total, metas, tasas, costos fijos) debajo de las fechas
        int totalsRowIdx = totalRow;
        int goalsRowIdx = -1;
        Map<String, Integer> rateRows = new HashMap<>();
        Map<String, Integer> costRows = new LinkedHashMap<>();
        boolean variableSection = false;
        boolean fixedSection = false;
        boolean fixedDone = false;
        int lastRow = Math.max(sheet.getLastRowNum(), lastDayRow);
        for (int r = Math.max(lastDayRow + 1, headerRow + 1); r <= lastRow; r++) {
            String label = normalizeAlias(textOf(cellAt(sheet, r, labelCol)));
            if (label.isEmpty()) {
                continue;
            }
            if (label.equals("METAS") && goalsRowIdx < 0) {
                goalsRowIdx = r;
            } else if (label.equals("COSTOS VARIABLES")) {
                variableSection = true;
            } else if (label.equals("COSTOS FIJOS")) {
                fixedSection = true;
                variableSection = false;
            } else if (variableSection) {
                if (label.startsWith("COSTO DEL") && !rateRows.containsKey("PRODUCT")) {
                    rateRows.put("PRODUCT", r);
                } else if (label.startsWith("COMISION DE VENTA") && !rateRows.containsKey("SALES")) {
                    rateRows.put("SALES", r);
                } else if (label.startsWith("COMISION TARJETA") && !rateRows.containsKey("CARD")) {
                    rateRows.put("CARD", r);
                } else if (label.equals("IVA") && !rateRows.containsKey("TAX")) {
                    rateRows.put("TAX", r);
                }
            } else if (fixedSection && !fixedDone) {
                // "Total costos fijos" es el rótulo del Excel que exporta Finanzas (antes "Total CI")
                if (label.equals("TOTAL CI") || label.equals("TOTAL COSTOS FIJOS")) {
                    fixedDone = true;
                    continue;
                }
                for (Map.Entry<String, String> p : FIXED_COST_PREFIXES.entrySet()) {
                    if (label.startsWith(p.getKey()) && !costRows.containsKey(p.getValue())) {
                        costRows.put(p.getValue(), r);
                        break;
                    }
                }
            }
        }
        if (goalsRowIdx < 0) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontró la fila 'METAS'; las metas quedan sin valor.", null, null, null, null, null);
        }
        if (rateRows.size() < 4) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontraron todas las filas de tasas (costo del producto, comisión de venta, tarjeta, IVA); "
                            + "las faltantes quedan sin valor.", null, null, null, null, null);
        }
        if (costRows.size() < COST_CODES.size()) {
            List<String> missing = new ArrayList<>(COST_CODES);
            missing.removeAll(costRows.keySet());
            missing.removeAll(OPTIONAL_COST_CODES);
            if (!missing.isEmpty()) {
                addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                        "No se encontraron filas de costo fijo para: " + String.join(", ", missing) + ".",
                        null, null, null, null, null);
            }
        }

        // 6) y 7) Lectura por kiosco y validaciones (compartidas con el formato 2026)
        final int goalsRow = goalsRowIdx;
        ConfigReader reader = new ConfigReader() {
            @Override
            public BigDecimal goal(int c, String name) {
                return goalsRow >= 0 ? configNumber(issues, sheet, goalsRow, c, name, 2, "meta") : null;
            }

            @Override
            public KioskExcelDataDto.Rates rates(int c, String name, BigDecimal monthSales) {
                // sólo la fila de tasa; la de abajo es calculada
                return KioskExcelDataDto.Rates.builder()
                        .productCostPct(rateRow(issues, sheet, rateRows, "PRODUCT", c, name, "costo del producto"))
                        .salesCommissionPct(rateRow(issues, sheet, rateRows, "SALES", c, name, "comisión de venta"))
                        .cardCommissionPct(rateRow(issues, sheet, rateRows, "CARD", c, name, "comisión de tarjeta"))
                        .taxPct(rateRow(issues, sheet, rateRows, "TAX", c, name, "IVA"))
                        .build();
            }

            @Override
            public Map<String, BigDecimal> costs(int c, String name) {
                Map<String, BigDecimal> kioskCosts = new LinkedHashMap<>();
                for (String code : COST_CODES) {
                    Integer r = costRows.get(code);
                    kioskCosts.put(code, r == null ? null : configNumber(issues, sheet, r, c, name, 2, code));
                }
                return kioskCosts;
            }
        };
        ParseResult result = collect(sheet, ym, columns, rowDates, totalsRowIdx, issues, reader);
        result.setFileName(fileName);
        result.setFormat(FORMAT_LEGACY);
        result.setPeriodSource(PERIOD_FROM_DATES);
        result.setPeriodEditable(false);
        return result;
    }

    /** Acceso a meta, tasas y costos de una columna de kiosco; cambia según el formato del Excel. */
    private interface ConfigReader {
        BigDecimal goal(int col, String name);

        /** @param monthSales ventas del mes del kiosco (fila Total de la hoja o suma recalculada). */
        KioskExcelDataDto.Rates rates(int col, String name, BigDecimal monthSales);

        Map<String, BigDecimal> costs(int col, String name);
    }

    /** Lee ventas por día, meta, tasas y costos de cada kiosco y valida (común a ambos formatos). */
    private ParseResult collect(Sheet sheet, YearMonth ym, List<ParsedColumn> columns, Map<Integer, LocalDate> rowDates,
                                int totalsRowIdx, List<KioskExcelIssueDto> issues, ConfigReader reader) {
        // Lectura por kiosco
        List<KioskExcelDataDto.Day> days = new ArrayList<>();
        List<Integer> inMonthRows = new ArrayList<>();
        for (Map.Entry<Integer, LocalDate> e : rowDates.entrySet()) {
            if (YearMonth.from(e.getValue()).equals(ym)) {
                inMonthRows.add(e.getKey());
                days.add(KioskExcelDataDto.Day.builder().date(e.getValue()).values(new LinkedHashMap<>()).build());
            }
        }

        List<KioskExcelDataDto.BlockedCell> blocked = new ArrayList<>();
        Map<String, BigDecimal> goals = new LinkedHashMap<>();
        Map<String, KioskExcelDataDto.Rates> rates = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> costs = new LinkedHashMap<>();
        Map<String, BigDecimal> sheetTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> recomputed = new LinkedHashMap<>();
        int salesCells = 0;

        for (ParsedColumn col : columns) {
            String name = col.getExcelName();
            int c = col.getColumnIndex();
            BigDecimal sum = BigDecimal.ZERO;
            List<BigDecimal> positives = new ArrayList<>();
            List<Object[]> valuesWithRef = new ArrayList<>(); // {row, value}

            for (Map.Entry<Integer, LocalDate> e : rowDates.entrySet()) {
                int r = e.getKey();
                LocalDate date = e.getValue();
                Raw raw = read(cellAt(sheet, r, c));
                boolean inMonth = YearMonth.from(date).equals(ym);
                if (raw.isBlank()) {
                    continue;
                }
                if (!inMonth) {
                    addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.OUT_OF_MONTH_VALUE,
                            "Valor fuera del mes " + ym + " (" + date + ") en '" + name + "': se ignora.",
                            name, date, ref(r, c), raw.display(), null);
                    continue;
                }
                BigDecimal value = null;
                if (raw.number != null) {
                    value = clean(raw.number, 4);
                    if (value.signum() < 0) {
                        KioskExcelIssueDto issue = addIssue(issues, KioskExcelIssueDto.BLOCKING,
                                KioskExcelIssueDto.NEGATIVE_VALUE,
                                "Venta negativa (" + value.toPlainString() + ") en '" + name + "' el " + date + ".",
                                name, date, ref(r, c), value.toPlainString(), null);
                        blocked.add(blockedCell(issue, KioskExcelIssueDto.NEGATIVE_VALUE));
                    }
                    salesCells++;
                } else {
                    String text = raw.display();
                    BigDecimal suggestion = raw.text != null ? repairNumericText(raw.text) : null;
                    KioskExcelIssueDto issue = addIssue(issues, KioskExcelIssueDto.BLOCKING,
                            KioskExcelIssueDto.NON_NUMERIC_CELL,
                            "Celda con texto '" + text + "' en '" + name + "' el " + date
                                    + (suggestion != null ? " (sugerido: " + suggestion.toPlainString() + ")" : "") + ".",
                            name, date, ref(r, c), text, suggestion);
                    blocked.add(blockedCell(issue, KioskExcelIssueDto.NON_NUMERIC_CELL));
                    if (suggestion != null) {
                        sum = sum.add(suggestion);
                    }
                }
                if (value != null) {
                    sum = sum.add(value);
                    if (value.signum() > 0) {
                        positives.add(value);
                    }
                    valuesWithRef.add(new Object[]{r, value});
                    days.get(inMonthRows.indexOf(r)).getValues().put(name, value);
                }
            }
            // Asegura la clave (null = celda vacía) en todos los días del mes
            for (KioskExcelDataDto.Day d : days) {
                d.getValues().putIfAbsent(name, null);
            }
            recomputed.put(name, clean(sum, 4));

            // Outliers (INFO): > 6x mediana de valores positivos y > 3000
            if (!positives.isEmpty()) {
                BigDecimal median = median(positives);
                for (Object[] vr : valuesWithRef) {
                    BigDecimal v = (BigDecimal) vr[1];
                    if (v.compareTo(OUTLIER_MIN_AMOUNT) > 0 && v.compareTo(median.multiply(OUTLIER_MEDIAN_FACTOR)) > 0) {
                        int r = (Integer) vr[0];
                        addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.OUTLIER,
                                "Venta atípica Q" + v.toPlainString() + " en '" + name + "' el " + rowDates.get(r)
                                        + " (mediana Q" + median.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                        + "). No se corrige.", name, rowDates.get(r), ref(r, c), v.toPlainString(), null);
                    }
                }
            }

            // Total de la hoja
            if (totalsRowIdx >= 0) {
                Raw t = read(cellAt(sheet, totalsRowIdx, c));
                if (t.number != null) {
                    sheetTotals.put(name, clean(t.number, 4));
                }
            }

            // Meta
            goals.put(name, reader.goal(c, name));

            // Tasas
            rates.put(name, reader.rates(c, name, sheetTotals.getOrDefault(name, recomputed.get(name))));

            // Costos fijos
            costs.put(name, reader.costs(c, name));
        }

        // Validaciones por kiosco: total de la hoja, costos y meta incompletos
        BigDecimal sheetTotalSum = BigDecimal.ZERO;
        BigDecimal recomputedSum = BigDecimal.ZERO;
        for (ParsedColumn col : columns) {
            String name = col.getExcelName();
            BigDecimal rec = recomputed.get(name);
            recomputedSum = recomputedSum.add(rec);
            BigDecimal sheet_ = sheetTotals.get(name);
            if (sheet_ != null) {
                sheetTotalSum = sheetTotalSum.add(sheet_);
                BigDecimal diff = rec.subtract(sheet_);
                if (diff.abs().compareTo(TOTAL_TOLERANCE) > 0) {
                    boolean hasSuggestedText = blocked.stream().anyMatch(b -> name.equals(b.getExcelName())
                            && KioskExcelIssueDto.NON_NUMERIC_CELL.equals(b.getCode()));
                    addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.TOTAL_MISMATCH,
                            "'" + name + "': el total de la hoja (Q" + sheet_.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                    + ") difiere de la suma recalculada (Q" + rec.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                    + "), diferencia Q" + diff.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                    + (hasSuggestedText ? "; la hoja no suma la celda de texto." : "."),
                            name, null, null, null, diff.setScale(2, RoundingMode.HALF_UP));
                }
            }
            boolean hasSales = rec.signum() > 0;
            if (hasSales) {
                List<String> missing = new ArrayList<>();
                Map<String, BigDecimal> kc = costs.get(name);
                for (String code : COST_CODES) {
                    if (kc.get(code) == null && !OPTIONAL_COST_CODES.contains(code)) {
                        missing.add(code);
                    }
                }
                KioskExcelDataDto.Rates rt = rates.get(name);
                if (rt.getProductCostPct() == null) {
                    missing.add("TASA_COSTO_PRODUCTO");
                }
                if (rt.getSalesCommissionPct() == null) {
                    missing.add("TASA_COMISION_VENTA");
                }
                if (rt.getCardCommissionPct() == null) {
                    missing.add("TASA_COMISION_TARJETA");
                }
                if (rt.getTaxPct() == null) {
                    missing.add("TASA_IVA");
                }
                if (!missing.isEmpty()) {
                    addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.MISSING_COSTS,
                            "'" + name + "' tiene ventas pero faltan costos/tasas: " + String.join(", ", missing) + ".",
                            name, null, null, null, null);
                }
                BigDecimal goal = goals.get(name);
                if (goal == null || goal.signum() <= 0) {
                    addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.MISSING_GOAL,
                            "'" + name + "' tiene ventas pero la meta está vacía o en 0.", name, null, null,
                            goal == null ? null : goal.toPlainString(), null);
                }
            }
        }

        KioskExcelDataDto data = KioskExcelDataDto.builder()
                .days(days).goals(goals).rates(rates).costs(costs).blockedCells(blocked).build();
        return ParseResult.builder()
                .sheetName(sheet.getSheetName())
                .year(ym.getYear())
                .month(ym.getMonthValue())
                .columns(columns)
                .issues(issues)
                .data(data)
                .days(days.size())
                .salesCells(salesCells)
                .recomputedTotals(recomputed)
                .sheetTotals(sheetTotals)
                .build();
    }

    // ------------------------------------------------------------------ formato 2026 (hojas "ventas 20XX")

    /** Hoja elegida del formato 2026 y las hojas que se omiten (año anterior y comparativos). */
    private record YearSheet(Sheet sheet, Integer year, int headerRow, List<String> ignored) {
    }

    /** @return {fila, columna de etiquetas} del encabezado "Fecha ... Total por día" o null. */
    private int[] findLegacyHeader(Sheet sheet) {
        for (int r = 0; r <= HEADER_SEARCH_ROWS; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c <= 2; c++) {
                if ("FECHA".equals(normalizeAlias(textOf(row.getCell(c)))) && hasTotalPorDia(row, c)) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private Sheet findLegacySheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet s = workbook.getSheetAt(i);
            if (!"HOJA1".equals(normalizeAlias(s.getSheetName())) && findLegacyHeader(s) != null) {
                return s;
            }
        }
        return null;
    }

    /** Fila (0-based) cuya columna A dice "kiosco" en las primeras filas, o -1. */
    private static int findKioscoHeaderRow(Sheet sheet) {
        for (int r = 0; r <= 4; r++) {
            if ("KIOSCO".equals(normalizeAlias(textOf(cellAt(sheet, r, 0))))) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Formato 2026: un libro con hojas "ventas 2025" / "ventas 2026" (+ "anita", "gabriela", "ANALISIS DE COSTO FIJO").
     * Se toma la hoja de ventas del año más reciente; el resto se omite.
     */
    private YearSheet findYearSheet(Workbook workbook) {
        Sheet best = null;
        Integer bestYear = null;
        int bestHeader = -1;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet s = workbook.getSheetAt(i);
            Matcher m = SHEET_YEAR_NAME.matcher(normalizeAlias(s.getSheetName()));
            int headerRow = findKioscoHeaderRow(s);
            if (m.matches() && headerRow >= 0) {
                int year = Integer.parseInt(m.group(1));
                if (bestYear == null || year > bestYear) {
                    best = s;
                    bestYear = year;
                    bestHeader = headerRow;
                }
            }
        }
        if (best == null) {
            return null;
        }
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet s = workbook.getSheetAt(i);
            if (s != best && !"HOJA1".equals(normalizeAlias(s.getSheetName()))) {
                ignored.add(s.getSheetName().trim());
            }
        }
        return new YearSheet(best, bestYear, bestHeader, ignored);
    }

    private ParseResult parseYearSheet(String fileName, YearSheet ys, YearMonth override) throws BusinessException {
        Sheet sheet = ys.sheet();
        int headerRow = ys.headerRow();
        final int labelCol = 0;
        List<KioskExcelIssueDto> issues = new ArrayList<>();
        if (!ys.ignored().isEmpty()) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "Se lee la hoja '" + sheet.getSheetName().trim() + "'. Hojas omitidas: " + String.join(", ", ys.ignored())
                            + " (año anterior, que ya se importa con su propio archivo, y comparativos).",
                    null, null, null, null, null);
        }

        // 1) Columnas de kiosco: desde B hasta "venta del día" / "acumulado" / columna con el año / "diferencia"
        Row header = sheet.getRow(headerRow);
        List<ParsedColumn> columns = new ArrayList<>();
        Map<String, String> seenNormalized = new HashMap<>();
        for (int c = labelCol + 1; c < header.getLastCellNum(); c++) {
            Raw cellValue = read(header.getCell(c));
            if (cellValue.isBlank()) {
                continue;
            }
            if (cellValue.number != null) {
                break; // columna con el año (p. ej. 2025): ya son comparativos
            }
            String raw = headerName(header.getCell(c));
            String norm = normalizeAlias(raw);
            if (norm.startsWith("VENTA DEL DIA") || norm.startsWith("ACUMULADO") || norm.startsWith("DIFERENCIA")
                    || norm.startsWith("TOTAL")) {
                break;
            }
            if (seenNormalized.containsKey(norm)) {
                addIssue(issues, KioskExcelIssueDto.BLOCKING, KioskExcelIssueDto.DUPLICATE_COLUMN,
                        "La columna '" + raw + "' está repetida en el encabezado (ya existe '" + seenNormalized.get(norm)
                                + "'); sólo se lee la primera.", raw, null, ref(headerRow, c), raw, null);
                continue;
            }
            seenNormalized.put(norm, raw);
            columns.add(ParsedColumn.builder().excelName(raw).normalized(norm).columnIndex(c).build());
        }
        if (columns.isEmpty()) {
            throw new BusinessException("No se encontraron columnas de kioscos en '" + fileName + "'.");
        }

        // 2) Filas de fecha: entre el encabezado y la fila "TOTAL"
        int totalRow = -1;
        for (int r = headerRow + 1; r <= headerRow + MAX_DAY_ROWS + 3; r++) {
            if ("TOTAL".equals(normalizeAlias(textOf(cellAt(sheet, r, labelCol))))) {
                totalRow = r;
                break;
            }
        }
        int lastDayRow;
        if (totalRow < 0) {
            lastDayRow = headerRow + MAX_DAY_ROWS;
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontró la fila 'TOTAL'; se asumen " + MAX_DAY_ROWS + " filas de fecha tras el encabezado.",
                    null, null, null, null, null);
        } else {
            lastDayRow = totalRow - 1;
        }

        // 3) Mes y año. Las fechas de la hoja no son fiables (la hoja se reutiliza de un mes a otro y arrastra el
        //    mes/año anterior): el año sale del nombre de la hoja, el mes del nombre del archivo, y el usuario puede
        //    corregirlos. De la columna de fechas sólo se usa el número de día.
        Map<YearMonth, Integer> dateVotes = new HashMap<>();
        for (int r = headerRow + 1; r <= lastDayRow; r++) {
            LocalDate d = dateOf(cellAt(sheet, r, labelCol));
            if (d != null) {
                dateVotes.merge(YearMonth.from(d), 1, Integer::sum);
            }
        }
        YearMonth dateMajority = null;
        int best = 0;
        for (Map.Entry<YearMonth, Integer> e : dateVotes.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dateMajority = e.getKey();
            }
        }
        Integer nameMonth = monthOnly(fileName);
        Integer nameYear = yearOnly(fileName);
        YearMonth ym;
        String periodSource;
        if (override != null) {
            ym = override;
            periodSource = PERIOD_OVERRIDE;
        } else {
            Integer year = ys.year() != null ? ys.year() : (nameYear != null ? nameYear
                    : (dateMajority != null ? dateMajority.getYear() : null));
            Integer month = nameMonth != null ? nameMonth : (dateMajority != null ? dateMajority.getMonthValue() : null);
            if (year == null || month == null) {
                throw new BusinessException("No se pudo determinar el mes de '" + fileName
                        + "': ponga el mes en el nombre del archivo (p. ej. \"reporte de ventas abril.xlsx\").");
            }
            ym = YearMonth.of(year, month);
            periodSource = nameMonth != null ? PERIOD_FROM_FILE_NAME : PERIOD_FROM_DATES;
        }
        if (override == null && dateMajority != null && !dateMajority.equals(ym)) {
            addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "Las fechas de la hoja indican " + dateMajority + " pero se usa " + ym + " (año de la hoja '"
                            + sheet.getSheetName().trim() + "' y mes " + (nameMonth != null ? "del nombre del archivo" : "de las fechas")
                            + "). Verifique el período; puede corregirlo en el asistente.",
                    null, null, null, null, null);
        }

        Map<Integer, LocalDate> rowDates = new TreeMap<>();
        Integer previousDay = null;
        boolean inferredDays = false;
        for (int r = headerRow + 1; r <= lastDayRow; r++) {
            LocalDate d = dateOf(cellAt(sheet, r, labelCol));
            int day;
            if (d != null) {
                day = d.getDayOfMonth();
            } else {
                day = previousDay != null ? previousDay + 1 : r - headerRow;
                inferredDays = true;
            }
            previousDay = day;
            // Filas de relleno (p. ej. 31 en un mes de 30 días) caen en el mes siguiente y se ignoran.
            rowDates.put(r, day <= ym.lengthOfMonth() ? ym.atDay(day)
                    : ym.atEndOfMonth().plusDays(day - ym.lengthOfMonth()));
        }
        if (inferredDays) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "Alguna celda de fecha no se pudo leer; se infirió como el día siguiente al anterior.",
                    null, null, null, null, null);
        }

        // 4) Filas etiquetadas debajo de las fechas: meta, costos variables (monto; la tasa va en la etiqueta) y fijos
        int goalsRow = -1;
        Map<String, Integer> rateRows = new HashMap<>();
        Map<String, BigDecimal> declaredRates = new HashMap<>();
        Map<String, Integer> costRows = new LinkedHashMap<>();
        boolean variable = false;
        boolean fixed = false;
        boolean fixedDone = false;
        int lastRow = Math.max(sheet.getLastRowNum(), lastDayRow);
        for (int r = lastDayRow + 1; r <= lastRow; r++) {
            String rawLabel = textOf(cellAt(sheet, r, labelCol));
            String label = normalizeAlias(rawLabel);
            if (label.isEmpty()) {
                continue;
            }
            if (label.equals("META") && goalsRow < 0) {
                goalsRow = r;
            } else if (label.equals("COSTOS VARIABLES")) {
                variable = true;
            } else if (label.equals("TOTAL CV") || label.equals("TOTAL COSTOS VARIABLES")) {
                variable = false;
            } else if (label.equals("COSTOS FIJOS")) {
                fixed = true;
                variable = false;
            } else if (label.equals("TOTAL CF") || label.equals("TOTAL COSTOS FIJOS")) {
                fixed = false;
                fixedDone = true;
            } else if (variable) {
                String key = null;
                if (label.startsWith("COSTO DEL")) {
                    key = "PRODUCT";
                } else if (label.startsWith("COMISION DE VENTA")) {
                    key = "SALES";
                } else if (label.startsWith("COMISION TARJETA")) {
                    key = "CARD";
                } else if (label.startsWith("IVA")) {
                    key = "TAX";
                }
                if (key != null && !rateRows.containsKey(key)) {
                    rateRows.put(key, r);
                    BigDecimal pct = percentInLabel(rawLabel);
                    if (pct != null) {
                        declaredRates.put(key, pct);
                    }
                }
            } else if (fixed && !fixedDone) {
                for (Map.Entry<String, String> p : FIXED_COST_PREFIXES.entrySet()) {
                    if (label.startsWith(p.getKey()) && !costRows.containsKey(p.getValue())) {
                        costRows.put(p.getValue(), r);
                        break;
                    }
                }
            }
        }
        if (goalsRow < 0) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontró la fila 'META'; las metas quedan sin valor.", null, null, null, null, null);
        }
        if (rateRows.size() < 4) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontraron todas las filas de costos variables (costo del producto, comisión de venta, tarjeta, "
                            + "IVA); las tasas faltantes quedan sin valor.", null, null, null, null, null);
        }
        List<String> missingRows = new ArrayList<>(COST_CODES);
        missingRows.removeAll(costRows.keySet());
        missingRows.removeAll(OPTIONAL_COST_CODES);
        if (!missingRows.isEmpty()) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontraron filas de costo fijo para: " + String.join(", ", missingRows) + ".",
                    null, null, null, null, null);
        }

        // 5) Lectura por kiosco (las tasas se derivan de los montos: monto / ventas del mes; la comisión de venta
        //    es la excepción: se importa la tasa nominal y el 70 % de la meta lo aplica el sistema)
        if (rateRows.get("SALES") != null) {
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "La comisión de venta se importa con su tasa nominal (la de la etiqueta o 4 %), no con el monto del Excel; "
                            + "el sistema la cobra solo a los kioscos que llegan al 70 % de su meta.",
                    null, null, null, null, null);
        }
        final int goalsRowIdx = goalsRow;
        final int activeKiosks = columns.size();
        final boolean[] supervisionNoted = {false};
        ConfigReader reader = new ConfigReader() {
            @Override
            public BigDecimal goal(int c, String name) {
                return goalsRowIdx >= 0 ? configNumber(issues, sheet, goalsRowIdx, c, name, 2, "meta") : null;
            }

            @Override
            public KioskExcelDataDto.Rates rates(int c, String name, BigDecimal monthSales) {
                return KioskExcelDataDto.Rates.builder()
                        .productCostPct(derivedRate(issues, sheet, rateRows, declaredRates, "PRODUCT", c, name, monthSales,
                                "costo del producto"))
                        .salesCommissionPct(nominalSalesCommission(rateRows, declaredRates))
                        .cardCommissionPct(derivedRate(issues, sheet, rateRows, declaredRates, "CARD", c, name, monthSales,
                                "comisión de tarjeta"))
                        .taxPct(derivedRate(issues, sheet, rateRows, declaredRates, "TAX", c, name, monthSales, "IVA"))
                        .build();
            }

            @Override
            public Map<String, BigDecimal> costs(int c, String name) {
                Map<String, BigDecimal> kioskCosts = new LinkedHashMap<>();
                for (String code : COST_CODES) {
                    Integer r = costRows.get(code);
                    kioskCosts.put(code, r == null ? null : configNumber(issues, sheet, r, c, name, 2, code));
                }
                if (kioskCosts.get(SUPERVISION) == null) {
                    BigDecimal computed = KioskSupervisionCost.compute(kioskCosts.get("SALARIOS_MO_INDIRECTA"),
                            kioskCosts.get("BONIFICACION"), activeKiosks);
                    if (computed != null) {
                        kioskCosts.put(SUPERVISION, computed);
                        if (!supervisionNoted[0]) {
                            supervisionNoted[0] = true;
                            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                                    "La fila 'supervisión' no existe o viene vacía; se calculó con ((Salarios MO indirecta + "
                                            + "Bonificación) × 2 × 14 / 12) / " + activeKiosks + " kioscos.",
                                    null, null, null, null, null);
                        }
                    }
                }
                return kioskCosts;
            }
        };
        ParseResult result = collect(sheet, ym, columns, rowDates, totalRow, issues, reader);
        result.setFileName(fileName);
        result.setFormat(FORMAT_SHEET_YEAR);
        result.setPeriodSource(periodSource);
        result.setPeriodEditable(true);
        return result;
    }

    /** Tasa nominal de la comisión de venta cuando la etiqueta no la declara. */
    static final BigDecimal DEFAULT_SALES_COMMISSION = new BigDecimal("0.04");

    /**
     * Comisión de venta del formato nuevo: se importa la tasa NOMINAL (la de la etiqueta, p. ej. "(4%)", o 4 %),
     * no la deducida del monto. El Excel pone 0 a los kioscos que no llegan al 70 % de su meta (o un monto
     * atípico por un error de fórmula) y deducir la tasa de ahí dejaría ese mes con una tasa errónea para siempre.
     * La condición del 70 % la aplica el sistema al calcular ({@code KioskPnlCalculator.CommissionPolicy}).
     * Si el archivo no trae la fila de comisión de venta, queda sin valor.
     */
    private BigDecimal nominalSalesCommission(Map<String, Integer> rows, Map<String, BigDecimal> declared) {
        if (rows.get("SALES") == null) {
            return null;
        }
        BigDecimal label = declared.get("SALES");
        return clean(label != null ? label : DEFAULT_SALES_COMMISSION, 4);
    }

    /**
     * Tasa de un concepto de costo variable: monto del kiosco / ventas del mes. Si coincide con el porcentaje de la
     * etiqueta (p. ej. "costo del producto (18%)") se usa el de la etiqueta; un monto 0 da tasa 0 (p. ej. comisión
     * de venta, que sólo aplica a algunos kioscos). Sin ventas no se puede derivar: queda sin valor.
     */
    private BigDecimal derivedRate(List<KioskExcelIssueDto> issues, Sheet sheet, Map<String, Integer> rows,
                                   Map<String, BigDecimal> declared, String key, int c, String name,
                                   BigDecimal monthSales, String what) {
        Integer r = rows.get(key);
        if (r == null) {
            return null;
        }
        BigDecimal amount = configNumber(issues, sheet, r, c, name, 6, "monto de " + what);
        if (amount == null || monthSales == null || monthSales.signum() <= 0) {
            return null;
        }
        BigDecimal ratio = amount.divide(monthSales, 6, RoundingMode.HALF_UP);
        BigDecimal label = declared.get(key);
        if (label != null && ratio.subtract(label).abs().compareTo(new BigDecimal("0.0005")) <= 0) {
            return clean(label, 4);
        }
        return clean(ratio, 4);
    }

    private static BigDecimal percentInLabel(String rawLabel) {
        if (rawLabel == null) {
            return null;
        }
        Matcher m = PERCENT_IN_LABEL.matcher(rawLabel);
        if (!m.find()) {
            return null;
        }
        try {
            return new BigDecimal(m.group(1).replace(',', '.')).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Nombre de archivo en palabras: sin acentos, MAYÚSCULAS y cualquier separador (punto, guion, guion bajo) como espacio. */
    private static String fileNameWords(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private static Integer monthOnly(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MONTH_ONLY.matcher(fileNameWords(text));
        return m.find() ? MONTHS.get(m.group(1)) : null;
    }

    private static Integer yearOnly(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = YEAR_ONLY.matcher(fileNameWords(text));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // ------------------------------------------------------------------ lectura de celdas

    /** Valor leído de una celda (los resultados de fórmula se leen del valor cacheado). */
    private static final class Raw {
        BigDecimal number;
        String text;
        String error;
        boolean blank = true;

        boolean isBlank() {
            return blank;
        }

        String display() {
            if (number != null) {
                return number.toPlainString();
            }
            return text != null ? text : (error != null ? error : "");
        }
    }

    private static Raw read(Cell cell) {
        Raw raw = new Raw();
        if (cell == null) {
            return raw;
        }
        try {
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) {
                type = cell.getCachedFormulaResultType();
            }
            switch (type) {
                case NUMERIC -> {
                    double d = cell.getNumericCellValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        raw.error = "NUM";
                    } else {
                        raw.number = BigDecimal.valueOf(d);
                    }
                    raw.blank = false;
                }
                case STRING -> {
                    String s = cell.getStringCellValue();
                    if (s != null) {
                        s = s.replace(' ', ' ').trim();
                    }
                    if (s != null && !s.isEmpty()) {
                        raw.text = s;
                        raw.blank = false;
                    }
                }
                case BOOLEAN -> {
                    raw.text = String.valueOf(cell.getBooleanCellValue());
                    raw.blank = false;
                }
                case ERROR -> {
                    FormulaError fe = FormulaError.forInt(cell.getErrorCellValue());
                    raw.error = fe == null ? "#ERROR" : fe.getString();
                    raw.blank = false;
                }
                default -> {
                    // BLANK / _NONE
                }
            }
        } catch (RuntimeException e) {
            raw.error = "#ERROR";
            raw.blank = false;
        }
        return raw;
    }

    private static Cell cellAt(Sheet sheet, int r, int c) {
        Row row = sheet.getRow(r);
        return row == null ? null : row.getCell(c);
    }

    private static String textOf(Cell cell) {
        Raw raw = read(cell);
        if (raw.text != null) {
            return raw.text;
        }
        return raw.number != null ? raw.number.toPlainString() : "";
    }

    private static String headerName(Cell cell) {
        String t = textOf(cell);
        return t.replace(' ', ' ').trim();
    }

    private static boolean hasTotalPorDia(Row row, int fromCol) {
        int last = row.getLastCellNum();
        for (int c = fromCol + 1; c < last; c++) {
            if (normalizeAlias(textOf(row.getCell(c))).startsWith("TOTAL POR D")) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate dateOf(Cell cell) {
        Raw raw = read(cell);
        if (raw.number == null) {
            return null;
        }
        double d = raw.number.doubleValue();
        if (d < 30000 || d > 80000) { // ~1982..2118: descarta números que no son fechas
            return null;
        }
        try {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static YearMonth monthFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MONTH_YEAR.matcher(normalizeAlias(text));
        if (m.find()) {
            return YearMonth.of(Integer.parseInt(m.group(2)), MONTHS.get(m.group(1)));
        }
        return null;
    }

    private static YearMonth monthFromTitle(Sheet sheet) {
        for (int r = 0; r <= 5; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c <= 5; c++) {
                YearMonth ym = monthFromText(textOf(row.getCell(c)));
                if (ym != null) {
                    return ym;
                }
            }
        }
        return null;
    }

    private BigDecimal configNumber(List<KioskExcelIssueDto> issues, Sheet sheet, int r, int c, String name,
                                    int scale, String what) {
        Raw raw = read(cellAt(sheet, r, c));
        if (raw.isBlank()) {
            return null;
        }
        if (raw.number != null) {
            return clean(raw.number, scale);
        }
        BigDecimal repaired = raw.text != null ? repairNumericText(raw.text) : null;
        addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.NON_NUMERIC_CELL,
                "Celda de texto '" + raw.display() + "' en " + what + " de '" + name + "': queda sin valor.",
                name, null, ref(r, c), raw.display(), repaired);
        return null;
    }

    private BigDecimal rateRow(List<KioskExcelIssueDto> issues, Sheet sheet, Map<String, Integer> rows, String key,
                               int c, String name, String what) {
        Integer r = rows.get(key);
        return r == null ? null : configNumber(issues, sheet, r, c, name, 4, "tasa de " + what);
    }

    // ------------------------------------------------------------------ utilidades

    private static KioskExcelDataDto.BlockedCell blockedCell(KioskExcelIssueDto issue, String code) {
        return KioskExcelDataDto.BlockedCell.builder()
                .issueId(issue.getId())
                .code(code)
                .excelName(issue.getExcelName())
                .date(issue.getDate())
                .cell(issue.getCell())
                .rawValue(issue.getRawValue())
                .build();
    }

    private static KioskExcelIssueDto addIssue(List<KioskExcelIssueDto> issues, String severity, String code,
                                               String message, String excelName, LocalDate date, String cell,
                                               String rawValue, BigDecimal suggestion) {
        KioskExcelIssueDto issue = KioskExcelIssueDto.builder()
                .id("i" + (issues.size() + 1))
                .severity(severity)
                .code(code)
                .message(message)
                .excelName(excelName)
                .date(date)
                .cell(cell)
                .rawValue(rawValue)
                .suggestion(suggestion)
                .build();
        issues.add(issue);
        return issue;
    }

    private static String ref(int row, int col) {
        return new CellReference(row, col).formatAsString();
    }

    /** Redondea y quita ceros a la derecha sin usar notación científica. */
    static BigDecimal clean(BigDecimal value, int scale) {
        BigDecimal v = value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
        if (v.scale() < 0) {
            v = v.setScale(0);
        }
        return v;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        BigDecimal[] arr = values.toArray(new BigDecimal[0]);
        Arrays.sort(arr);
        int n = arr.length;
        if (n % 2 == 1) {
            return arr[n / 2];
        }
        return arr[n / 2 - 1].add(arr[n / 2]).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }
}
