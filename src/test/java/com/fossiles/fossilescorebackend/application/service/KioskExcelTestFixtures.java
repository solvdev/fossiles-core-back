package com.fossiles.fossilescorebackend.application.service;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * Genera en memoria un Excel sintético con el mismo layout que los reales (febrero 2025):
 * etiquetas en la columna B, columna vacía intercalada (E), fechas que desbordan a marzo y etiquetas con U+FFFD.
 */
final class KioskExcelTestFixtures {

    private KioskExcelTestFixtures() {
    }

    static final String FIXED_LABEL_PHONE = "Telefono, Internet y programaci�n";
    static final String FIXED_LABEL_BONUS = "Bonificaci�n";

    /**
     * Kioscos: MIRAFLORES (C), PERI (D), [E vacía], NUEVO KIOSCO (F), "Total por día" (G), "Acumulado" (H).
     * Ventas: MIRAFLORES 100 por día (1-ene... aquí 1-feb vacío), PERI 50 por día, NUEVO KIOSCO 10 por día.
     *
     * @param peliText     si no es null, la celda de PERI el 10-feb es ese texto
     * @param peliNegative si es true, la celda de PERI el 11-feb es -5
     * @param spillValue   si no es null, MIRAFLORES el 2-mar (desborde) trae ese valor
     */
    static byte[] februaryWorkbook(String peliText, boolean peliNegative, Double spillValue) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Reporte de Vtas  orig.");
            wb.createSheet("Hoja1");
            CreationHelper helper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            sheet.createRow(1).createCell(1).setCellValue("VENTAS  KIOSCOS FEBRERO");
            sheet.createRow(2).createCell(2).setCellFormula("\"FEBRERO 2025\"");

            int header = 7; // fila 8 de Excel
            Row h = sheet.createRow(header);
            h.createCell(1).setCellValue("Fecha");
            h.createCell(2).setCellValue("MIRAFLORES ");
            h.createCell(3).setCellValue("PERI");
            h.createCell(5).setCellValue("NUEVO KIOSCO");
            h.createCell(6).setCellValue("Total por d�a");
            h.createCell(7).setCellValue("Acumulado");

            LocalDate start = LocalDate.of(2025, 2, 1);
            for (int i = 0; i < 31; i++) {
                LocalDate d = start.plusDays(i);
                Row row = sheet.createRow(header + 1 + i);
                var dc = row.createCell(1);
                dc.setCellValue(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()));
                dc.setCellStyle(dateStyle);
                boolean inMonth = d.getMonthValue() == 2;
                if (inMonth && i > 0) { // 1-feb vacío en todos (cerrado)
                    row.createCell(2).setCellValue(100);
                    if (peliText != null && d.getDayOfMonth() == 10) {
                        row.createCell(3).setCellValue(peliText);
                    } else if (peliNegative && d.getDayOfMonth() == 11) {
                        row.createCell(3).setCellValue(-5);
                    } else {
                        row.createCell(3).setCellValue(50);
                    }
                    row.createCell(5).setCellValue(10);
                }
                if (spillValue != null && d.equals(LocalDate.of(2025, 3, 2))) {
                    row.createCell(2).setCellValue(spillValue);
                }
            }
            int totalRow = header + 32;
            Row t = sheet.createRow(totalRow);
            t.createCell(1).setCellValue("Total");
            t.createCell(2).setCellValue(2700); // 27 días x 100
            t.createCell(3).setCellValue(1350 - (peliText != null ? 50 : 0) - (peliNegative ? 55 : 0));
            t.createCell(5).setCellValue(270);

            int r = totalRow + 1;
            label(sheet, r++, "% Participacion");
            Row goals = sheet.createRow(r++);
            goals.createCell(1).setCellValue("METAS");
            goals.createCell(2).setCellValue(130000);
            goals.createCell(3).setCellValue(95000);
            goals.createCell(5).setCellValue(0);
            label(sheet, r++, "% DE META");
            r += 2;
            label(sheet, r++, "COSTOS");
            label(sheet, r++, "Costos Variables");
            label(sheet, r++, "Fecha");
            r = rate(sheet, r, "Costo del Pdcto", 0.18, 0.18, 0.18);
            r = rate(sheet, r, "Comision de venta", 0.04, 0.04, 0.04);
            r = rate(sheet, r, "Comision tarjeta", 0.025, 0.02, 0.015);
            r = rate(sheet, r, "IVA", 0.025, 0.025, 0.025);
            label(sheet, r++, "Total CI");
            r++;
            label(sheet, r++, "Costos Fijos");
            String[] fixed = {"Alquiler", "Luz", FIXED_LABEL_PHONE, "Mantenimiento", "Salarios MO Indirecta",
                    FIXED_LABEL_BONUS, "Indemnizacion y vacaciones", "Bono 14", "Aguinaldo", "Salarios MO directa"};
            for (int i = 0; i < fixed.length; i++) {
                Row row = sheet.createRow(r++);
                row.createCell(1).setCellValue(fixed[i]);
                row.createCell(2).setCellValue(1000 + i);
                row.createCell(3).setCellValue(2000 + i);
                if (i != 0 && i != 1) { // NUEVO KIOSCO sin alquiler ni luz
                    row.createCell(5).setCellValue(3000 + i);
                }
            }
            Row totalCi = sheet.createRow(r++);
            totalCi.createCell(1).setCellValue("Total CI");
            totalCi.createCell(2).setCellValue(99999);
            label(sheet, r++, "Total Cto Oper.");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void label(Sheet sheet, int row, String text) {
        sheet.createRow(row).createCell(1).setCellValue(text);
    }

    /** Fila de tasa seguida de una fila calculada (sin etiqueta) que debe ignorarse. */
    private static int rate(Sheet sheet, int r, String label, double a, double b, double c) {
        Row row = sheet.createRow(r++);
        row.createCell(1).setCellValue(label);
        row.createCell(2).setCellValue(a);
        row.createCell(3).setCellValue(b);
        row.createCell(5).setCellValue(c);
        Row calc = sheet.createRow(r++);
        calc.createCell(2).setCellValue(777);
        calc.createCell(3).setCellValue(777);
        calc.createCell(5).setCellValue(777);
        return r;
    }
}
