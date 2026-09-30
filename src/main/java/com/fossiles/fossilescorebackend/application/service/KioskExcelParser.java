package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
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
    }

    public static final List<String> COST_CODES = List.copyOf(FIXED_COST_PREFIXES.values());

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
    }

    // ------------------------------------------------------------------ API

    public ParseResult parse(String fileName, byte[] content) throws BusinessException {
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo '" + fileName + "' está vacío.");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = pickSheet(workbook);
            if (sheet == null) {
                throw new BusinessException("El archivo '" + fileName + "' no contiene hojas.");
            }
            return parseSheet(fileName, sheet);
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
        int headerRow = -1;
        int labelCol = -1;
        for (int r = 0; r <= HEADER_SEARCH_ROWS && headerRow < 0; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c <= 2; c++) {
                if ("FECHA".equals(normalizeAlias(textOf(row.getCell(c)))) && hasTotalPorDia(row, c)) {
                    headerRow = r;
                    labelCol = c;
                    break;
                }
            }
        }
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
                if (label.equals("TOTAL CI")) {
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
            addIssue(issues, KioskExcelIssueDto.INFO, KioskExcelIssueDto.LAYOUT_ASSUMPTION,
                    "No se encontraron filas de costo fijo para: " + String.join(", ", missing) + ".",
                    null, null, null, null, null);
        }

        // 6) Lectura por kiosco
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
            BigDecimal goal = null;
            if (goalsRowIdx >= 0) {
                goal = configNumber(issues, sheet, goalsRowIdx, c, name, 2, "meta");
            }
            goals.put(name, goal);

            // Tasas (sólo la fila de tasa; la de abajo es calculada)
            rates.put(name, KioskExcelDataDto.Rates.builder()
                    .productCostPct(rateRow(issues, sheet, rateRows, "PRODUCT", c, name, "costo del producto"))
                    .salesCommissionPct(rateRow(issues, sheet, rateRows, "SALES", c, name, "comisión de venta"))
                    .cardCommissionPct(rateRow(issues, sheet, rateRows, "CARD", c, name, "comisión de tarjeta"))
                    .taxPct(rateRow(issues, sheet, rateRows, "TAX", c, name, "IVA"))
                    .build());

            // Costos fijos
            Map<String, BigDecimal> kioskCosts = new LinkedHashMap<>();
            for (String code : COST_CODES) {
                Integer r = costRows.get(code);
                kioskCosts.put(code, r == null ? null : configNumber(issues, sheet, r, c, name, 2, code));
            }
            costs.put(name, kioskCosts);
        }

        // 7) Validaciones por kiosco: total de la hoja, costos y meta incompletos
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
                    if (kc.get(code) == null) {
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
                .fileName(fileName)
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
