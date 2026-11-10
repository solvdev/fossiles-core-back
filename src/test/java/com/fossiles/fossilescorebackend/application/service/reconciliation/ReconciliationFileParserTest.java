package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationFileParser.ParsedReceipt;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationFileParser.RowError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationFileParserTest {

    @Test
    void csvConPuntoYComa_ConColumnasConAcentos_seInterpreta() throws Exception {
        String csv = "No. Guía;Monto Cobrado;Monto Envío;Referencia;Fecha;Transportista\n"
                + "58123456789;Q480.00;Q30.00;LIQ-FZ-312;12/10/2026;Forza Delivery\n"
                + "58123456790;\"1,250.50\";30;LIQ-FZ-312;2026-10-13;Forza Delivery\n";

        var sheet = ReconciliationFileParser.read(csv.getBytes(StandardCharsets.UTF_8), "liquidacion.csv");

        assertThat(sheet.rows()).hasSize(2);
        ParsedReceipt first = ReconciliationFileParser.map(sheet.rows().get(0));
        assertThat(first.guide()).isEqualTo("58123456789");
        assertThat(first.amount()).isEqualByComparingTo("480.00");
        assertThat(first.shipping()).isEqualByComparingTo("30.00");
        assertThat(first.reference()).isEqualTo("LIQ-FZ-312");
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(first.carrier()).isEqualTo("Forza Delivery");
        assertThat(ReconciliationFileParser.map(sheet.rows().get(1)).amount()).isEqualByComparingTo("1250.50");
    }

    @Test
    void excel_seLeeConFechasYNumeros() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Liquidación");
            Row header = sheet.createRow(0);
            String[] names = {"Guía", "Total", "Referencia", "Fecha"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(5812345678L);
            row.createCell(1).setCellValue(480.0);
            row.createCell(2).setCellValue("DEP-123456");
            var dateCell = row.createCell(3);
            dateCell.setCellValue(LocalDate.of(2026, 10, 12));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            dateCell.setCellStyle(style);
            wb.write(out);
        }

        var sheet = ReconciliationFileParser.read(out.toByteArray(), "extracto.xlsx");
        ParsedReceipt r = ReconciliationFileParser.map(sheet.rows().get(0));

        assertThat(r.guide()).isEqualTo("5812345678");
        assertThat(r.amount()).isEqualByComparingTo("480");
        assertThat(r.reference()).isEqualTo("DEP-123456");
        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void montos_seParsean_sinRedondear() throws Exception {
        assertThat(ReconciliationFileParser.parseMoney("Q1,234.50")).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThat(ReconciliationFileParser.parseMoney("1.234,50")).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThat(ReconciliationFileParser.parseMoney("480")).isEqualByComparingTo(new BigDecimal("480.00"));
        assertThat(ReconciliationFileParser.parseMoney("1,250")).isEqualByComparingTo(new BigDecimal("1250.00"));
        assertThat(ReconciliationFileParser.parseMoney("95,5")).isEqualByComparingTo(new BigDecimal("95.50"));
        assertThatThrownBy(() -> ReconciliationFileParser.parseMoney("100.005")).isInstanceOf(RowError.class).hasMessageContaining("más de 2 decimales");
        assertThatThrownBy(() -> ReconciliationFileParser.parseMoney("abc")).isInstanceOf(RowError.class);
    }

    @Test
    void fechas_enVariosFormatos() throws Exception {
        assertThat(ReconciliationFileParser.parseDate("2026-10-12")).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ReconciliationFileParser.parseDate("12/10/2026")).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ReconciliationFileParser.parseDate("12-10-2026 08:30")).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThatThrownBy(() -> ReconciliationFileParser.parseDate("ayer")).isInstanceOf(RowError.class);
    }

    @Test
    void columnaEnvio_conTextoEnvl_esElNumeroDeEnvio_yConNumero_esElMontoDelEnvio() throws Exception {
        var conEnvl = ReconciliationFileParser.read("Guia,Total,Envio\n111,480,ENVL-01601\n".getBytes(StandardCharsets.UTF_8), "a.csv");
        var conMonto = ReconciliationFileParser.read("Guia,Total,Envio\n111,480,30\n".getBytes(StandardCharsets.UTF_8), "b.csv");

        ParsedReceipt a = ReconciliationFileParser.map(conEnvl.rows().get(0));
        ParsedReceipt b = ReconciliationFileParser.map(conMonto.rows().get(0));

        assertThat(a.shipmentNumber()).isEqualTo("ENVL-01601");
        assertThat(a.shipping()).isNull();
        assertThat(b.shipmentNumber()).isNull();
        assertThat(b.shipping()).isEqualByComparingTo("30");
    }

    @Test
    void archivoSinColumnaDeMonto_seRechazaPorFila_yVacioSeRechazaAlLeer() {
        var sheet = assertDoesNotThrowRead("Guia,Referencia\n111,ABC\n");

        assertThat(ReconciliationFileParser.hasAmountColumn(sheet.headers())).isFalse();
        assertThatThrownBy(() -> ReconciliationFileParser.map(sheet.rows().get(0))).isInstanceOf(RowError.class);
        assertThatThrownBy(() -> ReconciliationFileParser.read(new byte[0], "x.csv")).isInstanceOf(BusinessException.class);
    }

    @Test
    void csvEnLatin1_conAcentos_seDecodifica() throws Exception {
        byte[] bytes = "Guía,Monto\n111,10\n".getBytes(StandardCharsets.ISO_8859_1);

        var sheet = ReconciliationFileParser.read(bytes, "x.csv");

        assertThat(sheet.headers()).contains("guia", "monto");
    }

    private ReconciliationFileParser.Sheet assertDoesNotThrowRead(String csv) {
        try {
            return ReconciliationFileParser.read(csv.getBytes(StandardCharsets.UTF_8), "x.csv");
        } catch (BusinessException e) {
            throw new AssertionError(e);
        }
    }
}
