package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.apache.poi.ss.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Lee el archivo de una fuente de fondos (liquidación del transportista, extracto, reporte de tarjeta) en Excel o CSV
 * y reconoce sus columnas por nombre. No se conocen todavía los formatos reales de Forza, Guatex ni del banco: por
 * eso las columnas se buscan por varios nombres posibles y lo que no se reconoce se informa, no se adivina.
 */
public final class ReconciliationFileParser {

    private ReconciliationFileParser() {
    }

    public record Sheet(List<String> headers, List<Map<String, String>> rows) {
    }

    /** Fila ya interpretada. */
    public record ParsedReceipt(String carrier, String guide, String reference, String authorization, String providerTxn,
                                String shipmentNumber, LocalDate date, BigDecimal amount, BigDecimal shipping,
                                Map<String, String> raw) {
    }

    public static class RowError extends Exception {
        public RowError(String message) {
            super(message);
        }
    }

    // ───────── lectura del archivo ─────────

    public static Sheet read(byte[] bytes, String fileName) throws BusinessException {
        if (bytes == null || bytes.length == 0) throw new BusinessException("El archivo está vacío.");
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<List<String>> table = (name.endsWith(".xlsx") || name.endsWith(".xls")) ? readExcel(bytes) : readCsv(bytes);
        int headerRow = 0;
        while (headerRow < table.size() && table.get(headerRow).stream().allMatch(String::isBlank)) headerRow++;
        if (headerRow >= table.size()) throw new BusinessException("El archivo no tiene filas.");
        List<String> headers = table.get(headerRow).stream().map(ReconciliationFileParser::headerKey).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = headerRow + 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(String::isBlank)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < cells.size(); c++) {
                if (!headers.get(c).isEmpty()) row.put(headers.get(c), cells.get(c).trim());
            }
            row.put("#fila", String.valueOf(i + 1));
            rows.add(row);
        }
        return new Sheet(headers, rows);
    }

    private static List<List<String>> readExcel(byte[] bytes) throws BusinessException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet0 s = new Sheet0(wb.getSheetAt(0));
            return s.table();
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo Excel: " + e.getMessage());
        }
    }

    private record Sheet0(org.apache.poi.ss.usermodel.Sheet sheet) {
        List<List<String>> table() {
            DataFormatter fmt = new DataFormatter(new Locale("es", "GT"));
            List<List<String>> out = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                width = Math.max(width, Math.max(0, row.getLastCellNum()));
            }
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < width; c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    cells.add(cell == null ? "" : cellText(cell, fmt));
                }
                out.add(cells);
            }
            return out;
        }

        private static String cellText(Cell cell, DataFormatter fmt) {
            CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (type == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            if (type == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
            if (type == CellType.STRING) return cell.getStringCellValue();
            return fmt.formatCellValue(cell);
        }
    }

    static List<List<String>> readCsv(byte[] bytes) {
        String text = decode(bytes);
        if (text.startsWith("﻿")) text = text.substring(1);
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = detectDelimiter(firstLine);
        List<List<String>> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cells.add(cur.toString());
                cur.setLength(0);
                rows.add(cells);
                cells = new ArrayList<>();
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0 || !cells.isEmpty()) {
            cells.add(cur.toString());
            rows.add(cells);
        }
        return rows;
    }

    private static String decode(byte[] bytes) {
        try {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static char detectDelimiter(String line) {
        char best = ',';
        int bestCount = -1;
        for (char d : new char[]{',', ';', '\t', '|'}) {
            int count = (int) line.chars().filter(c -> c == d).count();
            if (count > bestCount) {
                best = d;
                bestCount = count;
            }
        }
        return best;
    }

    /** "No. Guía" → "noguia". */
    public static String headerKey(String header) {
        if (header == null) return "";
        String n = Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ───────── interpretación de una fila ─────────

    private static final List<String> GUIDE = List.of("guia", "noguia", "numeroguia", "numerodeguia", "nroguia", "guianumero", "guide", "tracking", "guiatransportista", "numguia");
    private static final List<String> AMOUNT = List.of("monto", "montocobrado", "totalcobrado", "cobrado", "total", "importe", "valor", "amount", "montorecibido", "montoliquidado", "montoadepositar", "abono", "montototal", "valorcobrado");
    private static final List<String> SHIPPING = List.of("montoenvio", "costoenvio", "valorenvio", "flete", "shipping", "tarifa", "montodeenvio", "cargoenvio");
    private static final List<String> REFERENCE = List.of("referencia", "ref", "boleta", "noboleta", "deposito", "nodeposito", "numerodeboleta", "reference", "numerodeoperacion", "liquidacion", "noliquidacion", "comprobante", "documento", "nodocumento");
    private static final List<String> AUTH = List.of("autorizacion", "noautorizacion", "numeroautorizacion", "aprobacion", "authorization", "auth", "codigoautorizacion", "numautorizacion");
    private static final List<String> DATE = List.of("fecha", "fechapago", "fechadeposito", "fecharecibido", "fechaliquidacion", "date", "fechadepago");
    private static final List<String> CARRIER = List.of("transportista", "courier", "empresa", "carrier", "paqueteria");
    private static final List<String> TXN = List.of("transaccion", "idtransaccion", "txn", "idtxn", "notransaccion");
    private static final List<String> ENVL = List.of("envl", "numeroenvio", "noenvio", "numerodeenvio", "envio");

    /** Nombres de columna que se reconocen, para mostrar al usuario cuando un archivo no calza. */
    public static List<String> recognizedColumns() {
        return List.of("guía", "monto / total cobrado", "monto de envío", "referencia / boleta", "autorización",
                "fecha", "transportista", "ENVL / número de envío", "transacción");
    }

    public static boolean hasAmountColumn(Collection<String> headers) {
        return headers.stream().anyMatch(AMOUNT::contains);
    }

    public static ParsedReceipt map(Map<String, String> row) throws RowError {
        String amountText = first(row, AMOUNT);
        if (amountText == null) throw new RowError("No hay columna de monto en esta fila.");
        BigDecimal amount = parseMoney(amountText);
        if (amount.signum() < 0) throw new RowError("El monto no puede ser negativo: " + amountText);

        String shippingText = first(row, SHIPPING);
        BigDecimal shipping = shippingText == null ? null : parseMoney(shippingText);
        String envlText = first(row, ENVL);
        String shipmentNumber = null;
        if (envlText != null) {
            if (envlText.toUpperCase(Locale.ROOT).startsWith("ENVL")) {
                shipmentNumber = envlText.trim();
            } else if (shipping == null && looksNumericMoney(envlText) && row.containsKey("envio")) {
                shipping = parseMoney(envlText);   // columna "Envío" con el monto del envío
            }
        }
        String dateText = first(row, DATE);
        LocalDate date = dateText == null ? null : parseDate(dateText);
        return new ParsedReceipt(first(row, CARRIER), first(row, GUIDE), first(row, REFERENCE), first(row, AUTH), first(row, TXN),
                shipmentNumber, date, amount, shipping, row);
    }

    /** Guía de la fila, si el archivo trae una columna de guía. */
    public static String guideOf(Map<String, String> row) {
        return first(row, GUIDE);
    }

    /** ENVL de la fila (columna de número de envío con texto ENVL). */
    public static String envlOf(Map<String, String> row) {
        String v = first(row, ENVL);
        return v != null && v.toUpperCase(Locale.ROOT).startsWith("ENVL") ? v.trim() : null;
    }

    public static String carrierOf(Map<String, String> row) {
        return first(row, CARRIER);
    }

    private static String first(Map<String, String> row, List<String> keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static boolean looksNumericMoney(String s) {
        return s.trim().matches("^[Qq$]?\\s*[0-9][0-9.,\\s]*$");
    }

    /** "Q1,234.50", "1.234,50", "480" → BigDecimal. No redondea: más de 2 decimales se rechaza. */
    public static BigDecimal parseMoney(String text) throws RowError {
        String s = text.replaceAll("[Qq$\\s\\u00A0]", "");
        if (s.isEmpty()) throw new RowError("Monto vacío.");
        boolean negative = s.startsWith("-") || (s.startsWith("(") && s.endsWith(")"));
        s = s.replaceAll("[()\\-]", "");
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) s = s.replace(".", "").replace(',', '.');
            else s = s.replace(",", "");
        } else if (lastComma >= 0) {
            int decimals = s.length() - lastComma - 1;
            s = decimals == 3 && s.indexOf(',') == lastComma && s.length() > 4 ? s.replace(",", "") : s.replace(',', '.');
        }
        try {
            BigDecimal value = new BigDecimal(s);
            if (value.stripTrailingZeros().scale() > 2) throw new RowError("El monto tiene más de 2 decimales: " + text);
            BigDecimal scaled = value.setScale(2);
            return negative ? scaled.negate() : scaled;
        } catch (NumberFormatException e) {
            throw new RowError("Monto no válido: " + text);
        }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"), DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("d/M/yy"));

    public static LocalDate parseDate(String text) throws RowError {
        String s = text.trim();
        int cut = s.indexOf(' ');
        if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf('T');
        if (cut > 0) s = s.substring(0, cut);
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente formato
            }
        }
        throw new RowError("Fecha no válida: " + text);
    }
}
