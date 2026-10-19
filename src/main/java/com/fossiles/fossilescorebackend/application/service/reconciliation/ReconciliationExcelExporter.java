package com.fossiles.fossilescorebackend.application.service.reconciliation;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.money;
import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.sha256;

/**
 * Excel de la descarga de un lote. Se arma siempre desde las mismas filas congeladas del lote, y la huella SHA-256 se
 * calcula sobre el contenido de esas filas (no sobre los bytes del xlsx, que cambian con la fecha de creación).
 * El archivo es respaldo y consulta; el registro de la descarga es el libro del módulo.
 */
public final class ReconciliationExcelExporter {

    private ReconciliationExcelExporter() {
    }

    public record Row(Long caseId, LocalDate saleDate, String saleNumber, String shipmentNumber, String guide, String carrier,
                      String paymentMethod, String reference, BigDecimal expected, BigDecimal received, BigDecimal difference,
                      BigDecimal adjustment, String seller, BigDecimal commission, String status, String origin) {
    }

    public record Adjustment(String shipmentNumber, BigDecimal amount, String reason, String proposedBy, String authorizedBy,
                             LocalDateTime authorizedAt) {
    }

    public record Data(String code, LocalDate from, LocalDate to, String status, LocalDateTime closedAt, String closedBy,
                       String contentSha, int exportedCases, int closedCases, List<Row> rows, List<Adjustment> adjustments) {
    }

    /** Huella del contenido: mismas filas, misma huella. */
    public static String contentSha(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        rows.stream().sorted(Comparator.comparing(Row::caseId)).forEach(r -> sb
                .append(r.caseId()).append('|').append(r.saleDate()).append('|').append(nv(r.saleNumber())).append('|')
                .append(nv(r.shipmentNumber())).append('|').append(nv(r.guide())).append('|').append(nv(r.carrier())).append('|')
                .append(nv(r.paymentMethod())).append('|').append(nv(r.reference())).append('|').append(money(r.expected()).toPlainString())
                .append('|').append(money(r.received()).toPlainString()).append('|').append(money(r.difference()).toPlainString())
                .append('|').append(money(r.adjustment()).toPlainString()).append('|').append(nv(r.seller())).append('|')
                .append(money(r.commission()).toPlainString()).append('|').append(r.status()).append('|').append(r.origin()).append('\n'));
        return sha256(sb.toString());
    }

    public static byte[] build(Data d) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            summary(wb, st, d);
            detail(wb, st, d);
            adjustments(wb, st, d);
            commissions(wb, st, d);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel", e);
        }
    }

    private record Styles(CellStyle header, CellStyle money, CellStyle bold, CellStyle moneyBold) {
        Styles(Workbook wb) {
            this(style(wb, true, null), style(wb, false, "#,##0.00"), style(wb, true, null), style(wb, true, "#,##0.00"));
        }

        private static CellStyle style(Workbook wb, boolean bold, String format) {
            CellStyle s = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(bold);
            s.setFont(f);
            if (format != null) s.setDataFormat(wb.createDataFormat().getFormat(format));
            return s;
        }
    }

    private static void summary(Workbook wb, Styles st, Data d) {
        Sheet sh = wb.createSheet("Resumen");
        int r = 0;
        r = kv(sh, st, r, "Lote", d.code());
        r = kv(sh, st, r, "Período", d.from() + " a " + d.to());
        r = kv(sh, st, r, "Estado", d.status());
        r = kv(sh, st, r, "Cerrado por", nv(d.closedBy()));
        r = kv(sh, st, r, "Cerrado el", d.closedAt() == null ? "" : d.closedAt().toString());
        r = kv(sh, st, r, "Huella SHA-256 del contenido", d.contentSha());
        r = kv(sh, st, r, "Casos exportados", String.valueOf(d.exportedCases()));
        r = kv(sh, st, r, "Casos cerrados en el sistema", String.valueOf(d.closedCases()));
        r = kv(sh, st, r, "Exportados = cerrados", d.exportedCases() == d.closedCases() ? "Sí" : "NO COINCIDEN");
        BigDecimal exp = sum(d.rows(), Row::expected), rec = sum(d.rows(), Row::received), adj = sum(d.rows(), Row::adjustment);
        r = moneyLine(sh, st, r, "Total cobrado", exp);
        r = moneyLine(sh, st, r, "Total recibido", rec);
        r = moneyLine(sh, st, r, "Ajustes autorizados", adj);
        r = moneyLine(sh, st, r, "Saldo (cobrado − recibido − ajustes)", exp.subtract(rec).subtract(adj));
        r++;
        r = group(sh, st, r, "Por método de pago", d.rows(), Row::paymentMethod);
        group(sh, st, r + 1, "Por transportista", d.rows(), Row::carrier);
        sh.setColumnWidth(0, 12000);
        sh.setColumnWidth(1, 22000);
    }

    private static int group(Sheet sh, Styles st, int start, String title, List<Row> rows, java.util.function.Function<Row, String> key) {
        int r = start;
        Row0 head = new Row0(sh, r++);
        head.text(0, title, st.bold());
        head.text(1, "Casos", st.header());
        head.text(2, "Cobrado", st.header());
        head.text(3, "Recibido", st.header());
        head.text(4, "Ajustes", st.header());
        Map<String, List<Row>> groups = new TreeMap<>();
        for (Row row : rows) groups.computeIfAbsent(nv(key.apply(row)), k -> new ArrayList<>()).add(row);
        for (var e : groups.entrySet()) {
            Row0 line = new Row0(sh, r++);
            line.text(0, e.getKey(), null);
            line.number(1, e.getValue().size(), null);
            line.number(2, sum(e.getValue(), Row::expected), st.money());
            line.number(3, sum(e.getValue(), Row::received), st.money());
            line.number(4, sum(e.getValue(), Row::adjustment), st.money());
        }
        return r;
    }

    private static void detail(Workbook wb, Styles st, Data d) {
        Sheet sh = wb.createSheet("Detalle");
        String[] cols = {"Fecha", "Pedido", "ENVL", "Guía", "Transportista", "Método de pago", "Referencia", "Cobrado", "Recibido",
                "Diferencia", "Ajuste autorizado", "Vendedor", "Comisión (2% sin envío)", "Estado", "Origen"};
        Row0 head = new Row0(sh, 0);
        for (int i = 0; i < cols.length; i++) head.text(i, cols[i], st.header());
        int r = 1;
        for (Row x : d.rows().stream().sorted(Comparator.comparing(Row::saleDate).thenComparing(Row::caseId)).toList()) {
            Row0 line = new Row0(sh, r++);
            line.text(0, String.valueOf(x.saleDate()), null);
            line.text(1, nv(x.saleNumber()), null);
            line.text(2, nv(x.shipmentNumber()), null);
            line.text(3, nv(x.guide()), null);
            line.text(4, nv(x.carrier()), null);
            line.text(5, nv(x.paymentMethod()), null);
            line.text(6, nv(x.reference()), null);
            line.number(7, x.expected(), st.money());
            line.number(8, x.received(), st.money());
            line.number(9, x.difference(), st.money());
            line.number(10, x.adjustment(), st.money());
            line.text(11, nv(x.seller()), null);
            line.number(12, x.commission(), st.money());
            line.text(13, x.status(), null);
            line.text(14, x.origin(), null);
        }
        for (int i = 0; i < cols.length; i++) sh.setColumnWidth(i, i == 6 || i == 5 ? 6500 : 4200);
        sh.createFreezePane(0, 1);
    }

    private static void adjustments(Workbook wb, Styles st, Data d) {
        Sheet sh = wb.createSheet("Ajustes autorizados");
        String[] cols = {"ENVL", "Monto", "Motivo", "Propuso", "Autorizó", "Autorizado el"};
        Row0 head = new Row0(sh, 0);
        for (int i = 0; i < cols.length; i++) head.text(i, cols[i], st.header());
        int r = 1;
        for (Adjustment a : d.adjustments()) {
            Row0 line = new Row0(sh, r++);
            line.text(0, nv(a.shipmentNumber()), null);
            line.number(1, a.amount(), st.money());
            line.text(2, nv(a.reason()), null);
            line.text(3, nv(a.proposedBy()), null);
            line.text(4, nv(a.authorizedBy()), null);
            line.text(5, a.authorizedAt() == null ? "" : a.authorizedAt().toString(), null);
        }
        sh.setColumnWidth(0, 4500);
        sh.setColumnWidth(2, 16000);
        sh.setColumnWidth(3, 5000);
        sh.setColumnWidth(4, 5000);
        sh.setColumnWidth(5, 6500);
    }

    private static void commissions(Workbook wb, Styles st, Data d) {
        Sheet sh = wb.createSheet("Comisiones");
        Row0 head = new Row0(sh, 0);
        String[] cols = {"Vendedor", "Casos", "Comisión calculada"};
        for (int i = 0; i < cols.length; i++) head.text(i, cols[i], st.header());
        Map<String, List<Row>> bySeller = new TreeMap<>();
        for (Row row : d.rows()) bySeller.computeIfAbsent(nv(row.seller()).isEmpty() ? "(sin vendedor)" : row.seller(), k -> new ArrayList<>()).add(row);
        int r = 1;
        for (var e : bySeller.entrySet()) {
            Row0 line = new Row0(sh, r++);
            line.text(0, e.getKey(), null);
            line.number(1, e.getValue().size(), null);
            line.number(2, sum(e.getValue(), Row::commission), st.money());
        }
        sh.setColumnWidth(0, 8000);
        sh.setColumnWidth(2, 6000);
    }

    // ───────── apoyo ─────────

    private static int kv(Sheet sh, Styles st, int r, String key, String value) {
        Row0 line = new Row0(sh, r);
        line.text(0, key, st.bold());
        line.text(1, value, null);
        return r + 1;
    }

    private static int moneyLine(Sheet sh, Styles st, int r, String key, BigDecimal value) {
        Row0 line = new Row0(sh, r);
        line.text(0, key, st.bold());
        line.number(1, value, st.moneyBold());
        return r + 1;
    }

    private static BigDecimal sum(List<Row> rows, java.util.function.Function<Row, BigDecimal> f) {
        return money(rows.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }

    /** Fila de hoja con atajos para escribir celdas. */
    private static final class Row0 {
        private final org.apache.poi.ss.usermodel.Row row;

        Row0(Sheet sheet, int index) {
            this.row = sheet.createRow(index);
        }

        void text(int col, String value, CellStyle style) {
            Cell c = row.createCell(col);
            c.setCellValue(value);
            if (style != null) c.setCellStyle(style);
        }

        void number(int col, Number value, CellStyle style) {
            Cell c = row.createCell(col);
            c.setCellValue(value == null ? 0 : value.doubleValue());
            if (style != null) c.setCellStyle(style);
        }
    }
}
