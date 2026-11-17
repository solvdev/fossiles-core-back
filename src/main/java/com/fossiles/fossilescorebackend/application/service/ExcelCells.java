package com.fossiles.fossilescorebackend.application.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lectura de celdas de las plantillas de Excel: texto, montos y fechas tal como las escribe Contabilidad. */
final class ExcelCells {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private ExcelCells() {
    }

    /** Encabezados de la primera fila: nombre sin acentos ni mayúsculas → columna. */
    static Map<String, Integer> headerColumns(Row header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                String name = normalize(text(cell));
                if (!name.isEmpty()) {
                    columns.putIfAbsent(name, cell.getColumnIndex());
                }
            }
        }
        return columns;
    }

    static Cell cell(Row row, Map<String, Integer> columns, String header) {
        Integer index = columns.get(normalize(header));
        return index == null ? null : row.getCell(index);
    }

    static CellType effectiveType(Cell cell) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    }

    static boolean isBlank(Cell cell) {
        if (cell == null) {
            return true;
        }
        CellType type = effectiveType(cell);
        return type == CellType.BLANK || (type == CellType.STRING && text(cell).isEmpty());
    }

    static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (effectiveType(cell)) {
            case STRING:
                return cell.getStringCellValue() != null ? cell.getStringCellValue().trim() : "";
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return "";
                }
                return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    /** Acepta «346», «346.50», «Q346.50», «1,183.00» y «346,50». Lanza NumberFormatException si no es un número. */
    static BigDecimal parseAmount(Cell cell) {
        if (effectiveType(cell) == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (effectiveType(cell) != CellType.STRING) {
            throw new NumberFormatException("amount");
        }
        String value = text(cell).replace("Q", "").replace("q", "").replace(" ", "").replace(" ", "");
        if (value.contains(",") && value.contains(".")) {
            value = value.replace(",", "");
        } else if (value.contains(",")) {
            value = value.matches("\\d+,\\d{1,2}") ? value.replace(',', '.') : value.replace(",", "");
        }
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Fecha de Excel o texto «31/12/2026» o «2026-12-31». Lanza NumberFormatException si no es una fecha. */
    static LocalDate parseDate(Cell cell) {
        if (effectiveType(cell) == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value < 1) {
                throw new NumberFormatException("date");
            }
            return DateUtil.getLocalDateTime(value).toLocalDate();
        }
        String raw = text(cell);
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente formato
            }
        }
        throw new NumberFormatException("date");
    }

    static String normalize(String value) {
        String noAccents = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().toLowerCase(Locale.ROOT);
    }
}
