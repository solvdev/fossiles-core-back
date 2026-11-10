package com.fossiles.fossilescorebackend.application.service.onlineledger;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerRow;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.SellerCommission;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineSaleLedgerService.*;

/**
 * Excel del libro de ventas en línea con las hojas que lleva Contabilidad: Ventas (hoja principal), Cálculo de
 * comisiones por vendedor, Moto y Faltantes y sobrantes. Los totales y el cálculo de comisiones van como fórmulas
 * para que el archivo se pueda seguir trabajando.
 */
public final class OnlineSaleLedgerExcelExporter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String[] SALES_HEADERS = {
            "No.", "Nombre", "Dirección", "Teléfono", "Empaque", "Forma de Pago", "Factura", "Producto", "Color", "Talla",
            "Total Con Envío", "ENVÍO", "ENVIO REAL", "COMISION", "TRANSPORTE", "FECHA", "Red Social", "Correo", "VENDEDOR",
            "GUIA", "Envio", "Autorizacion", "ESTADO", "Observacion", "Sin envio", "Faltante (+) / Sobrante (-)",
            "Detalle faltante", "Pedido", "Revisado"};
    private static final int[] SALES_WIDTHS = {
            6, 28, 44, 16, 9, 20, 12, 36, 20, 12, 14, 10, 12, 11, 14, 12, 12, 26, 18, 18, 14, 22, 14, 36, 12, 14, 30, 9, 10};

    private static final String[] MOTO_HEADERS = {
            "LINEA", "Fecha de envio", "Fecha de recepcion", "No. envio", "Descripcion", "Monto sin envio", "Forma de pago",
            "Fecha de liquidacion", "No. de boleta", "Observacion", "Valor real deposito", "Observacion"};
    private static final int[] MOTO_WIDTHS = {8, 14, 16, 16, 40, 15, 20, 18, 16, 22, 18, 34};

    private OnlineSaleLedgerExcelExporter() {
    }

    public static byte[] build(LocalDate from, LocalDate to, List<LedgerRow> rows, List<SellerCommission> commissions) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            String period = DAY.format(from) + " al " + DAY.format(to);
            sales(wb, st, period, rows);
            commissions(wb, st, period, rows, commissions);
            moto(wb, st, period, rows);
            shortages(wb, st, period, rows);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel del libro de ventas en línea.", e);
        }
    }

    // ───────── Hoja principal ─────────

    private static void sales(XSSFWorkbook wb, Styles st, String period, List<LedgerRow> rows) {
        Sheet sh = wb.createSheet("Ventas");
        Row title = sh.createRow(1);
        Cell t = title.createCell(0);
        t.setCellValue("VENTAS EN LÍNEA — " + period);
        t.setCellStyle(st.title);

        Row head = sh.createRow(2);
        head.setHeightInPoints(30);
        for (int i = 0; i < SALES_HEADERS.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(SALES_HEADERS[i]);
            cell.setCellStyle(i <= 10 ? st.headLocked : st.head);
            sh.setColumnWidth(i, SALES_WIDTHS[i] * 256);
        }

        int r = 3;
        int n = 1;
        for (LedgerRow row : rows) {
            Tone tone = Tone.NONE; // Excel en limpio: sin color por transporte ni estado
            Row x = sh.createRow(r);
            int excelRow = r + 1;
            num(x, 0, n, st.text(tone));
            text(x, 1, row.getCustomerName(), st.text(tone));
            text(x, 2, row.getAddress(), st.text(tone));
            text(x, 3, row.getPhone(), st.text(tone));
            text(x, 4, Boolean.TRUE.equals(row.getPackaging()) ? "Sí" : "No", st.text(tone));
            text(x, 5, row.getPaymentMethodDisplay(), st.text(tone));
            text(x, 6, row.getInvoiceTaxId(), st.text(tone));
            text(x, 7, row.getProducts(), st.text(tone));
            text(x, 8, row.getColors(), st.text(tone));
            text(x, 9, row.getSizes(), st.text(tone));
            money(x, 10, nz(row.getTotalAmount()), st.money(tone));
            money(x, 11, nz(row.getShippingCost()), st.money(tone));
            money(x, 12, row.getRealShippingCost(), st.money(tone));
            money(x, 13, row.getCarrierCommission(), st.money(tone));
            text(x, 14, carrierLabel(row.getCarrier()), st.text(tone));
            date(x, 15, row.getSaleDate(), st.date(tone));
            text(x, 16, socialLabel(row.getSocialNetwork()), st.text(tone));
            text(x, 17, row.getEmail(), st.text(tone));
            text(x, 18, row.getSalesperson(), st.text(tone));
            text(x, 19, row.getGuideNumber(), st.text(tone));
            text(x, 20, row.getShipmentNumber(), st.text(tone));
            text(x, 21, clean(row.getAccountingAuthorization()).isEmpty() ? row.getPaymentAuthorization() : row.getAccountingAuthorization(), st.text(tone));
            text(x, 22, statusLabel(row.getStatus()), st.text(tone));
            text(x, 23, row.getAccountingNote(), st.text(tone));
            Cell net = x.createCell(24);
            if (row.getAccountingNet() != null) {
                net.setCellValue(row.getAccountingNet().doubleValue()); // «Sin envío» corregido a mano en el libro
            } else {
                net.setCellFormula("K" + excelRow + "-L" + excelRow);
            }
            net.setCellStyle(st.money(tone));
            money(x, 25, row.getShortageAmount(), st.money(tone));
            text(x, 26, row.getShortageNote(), st.text(tone));
            text(x, 27, row.getSaleNumber(), st.text(tone));
            text(x, 28, Boolean.TRUE.equals(row.getReviewed()) ? "Sí" : "", st.text(tone));
            r++;
            n++;
        }

        Row total = sh.createRow(r);
        Cell label = total.createCell(9);
        label.setCellValue("TOTALES");
        label.setCellStyle(st.totalLabel);
        for (int col : new int[]{10, 11, 12, 13, 24, 25}) {
            Cell cell = total.createCell(col);
            String letter = CellReference.convertNumToColString(col);
            cell.setCellFormula(rows.isEmpty() ? "0" : "SUM(" + letter + "4:" + letter + r + ")");
            cell.setCellStyle(st.totalMoney);
        }
        sh.createFreezePane(2, 3);
        if (!rows.isEmpty()) {
            sh.setAutoFilter(new CellRangeAddress(2, r - 1, 0, SALES_HEADERS.length - 1));
        }
    }

    // ───────── Comisiones ─────────

    private static void commissions(XSSFWorkbook wb, Styles st, String period, List<LedgerRow> rows,
                                    List<SellerCommission> commissions) {
        Sheet sh = wb.createSheet("CALCULO COMISIONES X VENDEDOR");
        sh.setColumnWidth(0, 3 * 256);
        sh.setColumnWidth(1, 5 * 256);
        sh.setColumnWidth(2, 30 * 256);
        sh.setColumnWidth(3, 4 * 256);
        sh.setColumnWidth(4, 16 * 256);
        sh.setColumnWidth(6, 4 * 256);
        sh.setColumnWidth(7, 46 * 256);
        sh.setColumnWidth(8, 16 * 256);

        Row title = sh.createRow(0);
        Cell t = title.createCell(2);
        t.setCellValue("CÁLCULO DE COMISIONES POR VENDEDOR — " + period);
        t.setCellStyle(st.title);

        String pct = COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
        List<String[]> payRefs = new ArrayList<>();
        int r = 2;
        for (SellerCommission sc : commissions) {
            Row name = row(sh, r);
            Cell nameCell = name.createCell(2);
            nameCell.setCellValue(sc.getSeller().toUpperCase(Locale.ROOT));
            nameCell.setCellStyle(st.seller);
            int first = r + 2; // fila Excel (1-based) de VENTAS
            line(sh, st, r + 1, null, "VENTAS (" + sc.getSalesCount() + ")", sc.getSales(), null, false);
            line(sh, st, r + 2, "(-)", "DEVOLUCIONES (" + sc.getReturnsCount() + ")", sc.getReturns(), null, false);
            line(sh, st, r + 3, "(-)", "ANULADAS (" + sc.getVoidedCount() + ")", sc.getVoided(), null, false);
            line(sh, st, r + 4, "(-)", "ENVIO REAL", sc.getRealShipping(), null, false);
            line(sh, st, r + 5, "(-)", "COMISIONES REAL", sc.getCarrierCommissions(), null, false);
            line(sh, st, r + 6, null, "SUB TOTAL", null,
                    "E" + first + "-E" + (first + 1) + "-E" + (first + 2) + "-E" + (first + 3) + "-E" + (first + 4), true);
            line(sh, st, r + 7, "(-)", "IVA", null, "E" + (first + 5) + "/1.12*12%", false);
            line(sh, st, r + 8, null, "TOTAL VENTAS NETAS", null, "E" + (first + 5) + "-E" + (first + 6), true);
            line(sh, st, r + 9, null, pct + " COMISIONES", null, "E" + (first + 7) + "*" + COMMISSION_RATE.toPlainString(), false);
            line(sh, st, r + 10, "(-)", "DESCUENTO DE FALTANTES", sc.getShortages(), null, false);
            line(sh, st, r + 11, null, "TOTAL COMISIONES A PAGAR", null, "E" + (first + 8) + "-E" + (first + 9), true);
            payRefs.add(new String[]{sc.getSeller(), "E" + (first + 10)});
            r += 14;
        }

        // Dato de contabilidad: el mismo cálculo sobre todas las ventas, separado por transporte.
        BigDecimal moto = BigDecimal.ZERO, guatex = BigDecimal.ZERO, forza = BigDecimal.ZERO, other = BigDecimal.ZERO;
        BigDecimal realShipping = BigDecimal.ZERO, comForza = BigDecimal.ZERO, comGuatex = BigDecimal.ZERO, comOther = BigDecimal.ZERO;
        BigDecimal returns = BigDecimal.ZERO, voided = BigDecimal.ZERO, shortages = BigDecimal.ZERO;
        int returnsCount = 0, voidedCount = 0;
        for (LedgerRow row : rows) {
            BigDecimal total = nz(row.getTotalAmount());
            String carrier = clean(row.getCarrier()).toUpperCase(Locale.ROOT);
            if (carrier.startsWith("MOTO")) moto = moto.add(total);
            else if (carrier.startsWith("GUATEX")) guatex = guatex.add(total);
            else if (carrier.startsWith("FORZA")) forza = forza.add(total);
            else other = other.add(total);
            realShipping = realShipping.add(nz(row.getRealShippingCost()));
            if (carrier.startsWith("FORZA")) comForza = comForza.add(nz(row.getCarrierCommission()));
            else if (carrier.startsWith("GUATEX")) comGuatex = comGuatex.add(nz(row.getCarrierCommission()));
            else comOther = comOther.add(nz(row.getCarrierCommission()));
            if (isReturn(row.getStatus())) {
                returnsCount++;
                returns = returns.add(total);
            } else if (isVoided(row.getStatus())) {
                voidedCount++;
                voided = voided.add(total);
            }
            shortages = shortages.add(nz(row.getShortageAmount()));
        }
        int a = 2;
        Cell head = row(sh, a).createCell(7);
        head.setCellValue("DATO DE CONTABILIDAD");
        head.setCellStyle(st.seller);
        side(sh, st, a + 1, "VENTA MOTORISTA", moto, null, false);
        side(sh, st, a + 2, "VENTA GUATEX", guatex, null, false);
        side(sh, st, a + 3, "VENTA FORZA", forza, null, false);
        side(sh, st, a + 4, "VENTA OTROS / SIN TRANSPORTE", other, null, false);
        side(sh, st, a + 5, "TOTAL VENTAS", null, "SUM(I" + (a + 2) + ":I" + (a + 5) + ")", true);
        side(sh, st, a + 6, "(-) PAGO DE ENVIO REAL", realShipping, null, false);
        side(sh, st, a + 7, "(-) COMISIONES SERVICIO DE TRANSPORTE FORZA", comForza, null, false);
        side(sh, st, a + 8, "(-) COMISIONES SERVICIO DE TRANSPORTE GUATEX", comGuatex, null, false);
        side(sh, st, a + 9, "(-) OTRAS COMISIONES DE TRANSPORTE", comOther, null, false);
        side(sh, st, a + 10, "(-) " + returnsCount + " DEVOLUCIONES", returns, null, false);
        side(sh, st, a + 11, "(-) " + voidedCount + " ANULADAS", voided, null, false);
        side(sh, st, a + 12, "SUB TOTAL", null, "I" + (a + 6) + "-SUM(I" + (a + 7) + ":I" + (a + 12) + ")", true);
        side(sh, st, a + 13, "(-) IVA", null, "I" + (a + 13) + "/1.12*12%", false);
        side(sh, st, a + 14, "TOTAL VENTAS NETAS", null, "I" + (a + 13) + "-I" + (a + 14), true);
        side(sh, st, a + 15, pct + " COMISIONES", null, "I" + (a + 15) + "*" + COMMISSION_RATE.toPlainString(), false);
        side(sh, st, a + 16, "(-) FALTANTE A DESCONTAR", shortages, null, false);
        side(sh, st, a + 17, "TOTAL COMISIONES A PAGAR", null, "I" + (a + 16) + "-I" + (a + 17), true);

        int p = a + 20;
        Cell payHead = row(sh, p).createCell(7);
        payHead.setCellValue("TOTAL A PAGAR COMISIONES");
        payHead.setCellStyle(st.seller);
        int k = p + 1;
        for (String[] ref : payRefs) {
            side(sh, st, k, ref[0].toUpperCase(Locale.ROOT), null, ref[1], false);
            k++;
        }
        if (!payRefs.isEmpty()) {
            side(sh, st, k, "TOTAL", null, "SUM(I" + (p + 2) + ":I" + k + ")", true);
        }
    }

    private static void line(Sheet sh, Styles st, int rowIdx, String sign, String label, BigDecimal value, String formula,
                             boolean bold) {
        Row row = row(sh, rowIdx);
        if (sign != null) {
            Cell s = row.createCell(1);
            s.setCellValue(sign);
            s.setCellStyle(st.plain);
        }
        Cell l = row.createCell(2);
        l.setCellValue(label);
        l.setCellStyle(bold ? st.plainBold : st.plain);
        Cell v = row.createCell(4);
        if (formula != null) v.setCellFormula(formula);
        else v.setCellValue(nz(value).doubleValue());
        v.setCellStyle(bold ? st.totalMoney : st.plainMoney);
    }

    private static void side(Sheet sh, Styles st, int rowIdx, String label, BigDecimal value, String formula, boolean bold) {
        Row row = row(sh, rowIdx);
        Cell l = row.createCell(7);
        l.setCellValue(label);
        l.setCellStyle(bold ? st.plainBold : st.plain);
        Cell v = row.createCell(8);
        if (formula != null) v.setCellFormula(formula);
        else v.setCellValue(nz(value).doubleValue());
        v.setCellStyle(bold ? st.totalMoney : st.plainMoney);
    }

    // ───────── Moto ─────────

    private static void moto(XSSFWorkbook wb, Styles st, String period, List<LedgerRow> rows) {
        Sheet sh = wb.createSheet("MOTO");
        Cell t = sh.createRow(1).createCell(0);
        t.setCellValue("VENTAS DE MOTORISTA — " + period);
        t.setCellStyle(st.title);
        Row head = sh.createRow(2);
        head.setHeightInPoints(30);
        for (int i = 0; i < MOTO_HEADERS.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(MOTO_HEADERS[i]);
            cell.setCellStyle(i == 0 || (i >= 3 && i <= 5) ? st.headLocked : st.head);
            sh.setColumnWidth(i, MOTO_WIDTHS[i] * 256);
        }
        int r = 3;
        int n = 0;
        int first = r + 1;
        for (LedgerRow row : rows) {
            n++;
            if (!clean(row.getCarrier()).toUpperCase(Locale.ROOT).startsWith("MOTO")) {
                continue;
            }
            Row x = sh.createRow(r);
            num(x, 0, n, st.text(Tone.NONE));
            date(x, 1, row.getSentDate() != null ? row.getSentDate() : row.getDispatchedDate(), st.date(Tone.NONE));
            date(x, 2, row.getReceivedDate(), st.date(Tone.NONE));
            text(x, 3, clean(row.getShipmentNumber()).isEmpty() ? row.getGuideNumber() : row.getShipmentNumber(), st.text(Tone.NONE));
            text(x, 4, row.getProducts(), st.text(Tone.NONE));
            money(x, 5, nz(row.getNetAmount()), st.money(Tone.NONE));
            text(x, 6, clean(row.getPaymentForm()).isEmpty() ? row.getPaymentMethodDisplay() : row.getPaymentForm(), st.text(Tone.NONE));
            date(x, 7, row.getLiquidationDate(), st.date(Tone.NONE));
            text(x, 8, row.getDepositSlipNumber(), st.text(Tone.NONE));
            text(x, 9, clean(row.getDeliveryNote()).isEmpty() ? statusLabel(row.getStatus()) : row.getDeliveryNote(), st.text(Tone.NONE));
            money(x, 10, row.getRealDepositAmount(), st.money(Tone.NONE));
            text(x, 11, row.getDepositNote(), st.text(Tone.NONE));
            r++;
        }
        Row total = sh.createRow(r);
        Cell label = total.createCell(4);
        label.setCellValue("TOTALES");
        label.setCellStyle(st.totalLabel);
        for (int col : new int[]{5, 10}) {
            Cell cell = total.createCell(col);
            String letter = CellReference.convertNumToColString(col);
            cell.setCellFormula(r < first ? "0" : "SUM(" + letter + first + ":" + letter + r + ")");
            cell.setCellStyle(st.totalMoney);
        }
        sh.createFreezePane(1, 3);
    }

    // ───────── Faltantes y sobrantes ─────────

    private static void shortages(XSSFWorkbook wb, Styles st, String period, List<LedgerRow> rows) {
        Sheet sh = wb.createSheet("FALTANTE Y SOBRANTES");
        Cell t = sh.createRow(1).createCell(0);
        t.setCellValue("FALTANTES Y SOBRANTES DE VENTA EN LÍNEA — " + period);
        t.setCellStyle(st.title);
        String[] headers = {"FECHA", "ENVIO", "DESCRIPCION", "TIPO", "VALOR", "VENDEDOR", "TRANSPORTE"};
        int[] widths = {12, 14, 44, 12, 12, 20, 14};
        Row head = sh.createRow(2);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(st.head);
            sh.setColumnWidth(i, widths[i] * 256);
        }
        int r = 3;
        Map<String, BigDecimal> bySeller = new LinkedHashMap<>();
        for (LedgerRow row : rows) {
            BigDecimal amount = row.getShortageAmount();
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            Row x = sh.createRow(r++);
            date(x, 0, row.getSaleDate(), st.date(Tone.NONE));
            text(x, 1, row.getShipmentNumber(), st.text(Tone.NONE));
            text(x, 2, row.getShortageNote(), st.text(Tone.NONE));
            text(x, 3, amount.signum() > 0 ? "FALTANTE" : "SOBRANTE", st.text(Tone.NONE));
            money(x, 4, amount.abs(), st.money(Tone.NONE));
            text(x, 5, row.getSalesperson(), st.text(Tone.NONE));
            text(x, 6, carrierLabel(row.getCarrier()), st.text(Tone.NONE));
            String seller = clean(row.getSalesperson()).isEmpty() ? "Sin vendedor" : clean(row.getSalesperson());
            bySeller.merge(seller, amount, BigDecimal::add);
        }
        r++;
        for (Map.Entry<String, BigDecimal> e : bySeller.entrySet()) {
            Row x = sh.createRow(r++);
            Cell l = x.createCell(2);
            l.setCellValue("FALTANTE NETO " + e.getKey().toUpperCase(Locale.ROOT));
            l.setCellStyle(st.plainBold);
            Cell v = x.createCell(4);
            v.setCellValue(e.getValue().doubleValue());
            v.setCellStyle(st.totalMoney);
        }
        if (bySeller.isEmpty()) {
            Cell none = sh.createRow(3).createCell(0);
            none.setCellValue("Sin faltantes ni sobrantes anotados en el periodo.");
            none.setCellStyle(st.plain);
        }
    }

    // ───────── helpers ─────────

    private static Row row(Sheet sh, int idx) {
        Row row = sh.getRow(idx);
        return row != null ? row : sh.createRow(idx);
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null && !value.isBlank()) cell.setCellValue(value.trim());
        cell.setCellStyle(style);
    }

    private static void num(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void money(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void date(Row row, int col, LocalDate value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    static String carrierLabel(String carrier) {
        String c = clean(carrier).toUpperCase(Locale.ROOT);
        if (c.startsWith("FORZA")) return "FORZA";
        return c.replace('_', ' ');
    }

    /** Red social con su nombre (WHATSAPP → WhatsApp). */
    static String socialLabel(String social) {
        String s = clean(social).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "" -> "";
            case "WHATSAPP" -> "WhatsApp";
            case "FACEBOOK" -> "Facebook";
            case "INSTAGRAM" -> "Instagram";
            case "TIKTOK" -> "TikTok";
            case "WEB", "PAGINA_WEB" -> "Página web";
            default -> s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    static String statusLabel(String status) {
        String s = clean(status).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "ANULADA" -> "ANULADO";
            case "DEVOLUCION" -> "DEVOLUCIÓN";
            case "EN_PRODUCCION" -> "EN PRODUCCIÓN";
            default -> s.replace('_', ' ');
        };
    }

    /** Color de la fila, como la leyenda de la hoja original: el estado manda sobre el transporte. */
    enum Tone {
        NONE("", null),
        MOTO("MOTORISTA", "FFF2CC"),
        GUATEX("GUATEX", "BDE3F7"),
        FORZA("FORZA", "FBD5A8"),
        RETURN("DEVOLUCION", "E4C1F9"),
        VOID("ANULADO", "FF9B9B"),
        EXCHANGE("CAMBIO", "D9D9D9");

        final String label;
        final String rgb;

        Tone(String label, String rgb) {
            this.label = label;
            this.rgb = rgb;
        }

        static Tone of(LedgerRow row) {
            if (isVoided(row.getStatus())) return VOID;
            if (isReturn(row.getStatus())) return RETURN;
            if (clean(row.getObservations()).toUpperCase(Locale.ROOT).startsWith("CAMBIO")) return EXCHANGE;
            String carrier = clean(row.getCarrier()).toUpperCase(Locale.ROOT);
            if (carrier.startsWith("MOTO")) return MOTO;
            if (carrier.startsWith("GUATEX")) return GUATEX;
            if (carrier.startsWith("FORZA")) return FORZA;
            return NONE;
        }
    }

    private static final class Styles {
        final XSSFWorkbook wb;
        final CellStyle title;
        final CellStyle head;
        final CellStyle headLocked;
        final CellStyle seller;
        final CellStyle plain;
        final CellStyle plainBold;
        final CellStyle plainMoney;
        final CellStyle totalMoney;
        final CellStyle totalLabel;
        private final Map<Tone, CellStyle> text = new EnumMap<>(Tone.class);
        private final Map<Tone, CellStyle> money = new EnumMap<>(Tone.class);
        private final Map<Tone, CellStyle> date = new EnumMap<>(Tone.class);
        private final Map<Tone, CellStyle> legend = new EnumMap<>(Tone.class);
        private final short moneyFormat;
        private final short dateFormat;

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            DataFormat df = wb.createDataFormat();
            moneyFormat = df.getFormat("#,##0.00");
            dateFormat = df.getFormat("dd/mm/yyyy");

            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            XSSFFont big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 13);

            title = wb.createCellStyle();
            title.setFont(big);

            // Encabezados en limpio: negrita, sin relleno de color.
            head = header(bold, null);
            headLocked = header(bold, null);

            seller = wb.createCellStyle();
            seller.setFont(big);

            plain = wb.createCellStyle();
            plainBold = wb.createCellStyle();
            plainBold.setFont(bold);
            plainMoney = wb.createCellStyle();
            plainMoney.setDataFormat(moneyFormat);
            totalMoney = wb.createCellStyle();
            totalMoney.setDataFormat(moneyFormat);
            totalMoney.setFont(bold);
            totalMoney.setBorderTop(BorderStyle.THIN);
            totalMoney.setBorderBottom(BorderStyle.DOUBLE);
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(bold);
            totalLabel.setAlignment(HorizontalAlignment.RIGHT);
        }

        private CellStyle header(XSSFFont font, String rgb) {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFont(font);
            fill(style, rgb);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            border(style);
            return style;
        }

        CellStyle text(Tone tone) {
            return text.computeIfAbsent(tone, t -> cell(t, (short) 0));
        }

        CellStyle money(Tone tone) {
            return money.computeIfAbsent(tone, t -> cell(t, moneyFormat));
        }

        CellStyle date(Tone tone) {
            return date.computeIfAbsent(tone, t -> cell(t, dateFormat));
        }

        CellStyle legend(Tone tone) {
            return legend.computeIfAbsent(tone, t -> {
                XSSFCellStyle style = wb.createCellStyle();
                XSSFFont bold = wb.createFont();
                bold.setBold(true);
                style.setFont(bold);
                fill(style, t.rgb);
                style.setAlignment(HorizontalAlignment.CENTER);
                border(style);
                return style;
            });
        }

        private CellStyle cell(Tone tone, short format) {
            XSSFCellStyle style = wb.createCellStyle();
            if (format != 0) style.setDataFormat(format);
            if (tone.rgb != null) fill(style, tone.rgb);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            border(style);
            return style;
        }

        private static void fill(XSSFCellStyle style, String rgb) {
            if (rgb == null) return;
            byte[] bytes = {
                    (byte) Integer.parseInt(rgb.substring(0, 2), 16),
                    (byte) Integer.parseInt(rgb.substring(2, 4), 16),
                    (byte) Integer.parseInt(rgb.substring(4, 6), 16)};
            style.setFillForegroundColor(new XSSFColor(bytes, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static void border(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            short grey = IndexedColors.GREY_40_PERCENT.getIndex();
            style.setTopBorderColor(grey);
            style.setBottomBorderColor(grey);
            style.setLeftBorderColor(grey);
            style.setRightBorderColor(grey);
        }
    }
}
