package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import com.fossiles.fossilescorebackend.application.util.KioskSupervisionCost;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formato de reporte 2026 (hojas "ventas 2025" / "ventas 2026"), contra los archivos reales de abril y mayo.
 * Se omite si la carpeta de reportes no está disponible.
 */
class KioskExcelParserSheetYearTest {

    private static final KioskExcelParser PARSER = new KioskExcelParser();

    private static Path folder() {
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("kiosk.excel.dir");
        if (override != null) {
            candidates.add(Paths.get(override));
        }
        candidates.add(Paths.get("..", "Documentacion", "reportesventas"));
        candidates.add(Paths.get("C:\\Users\\eduar\\Desktop\\Work\\Fossiles\\Documentacion\\reportesventas"));
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(null);
    }

    private static byte[] read(String name) throws IOException {
        Path folder = folder();
        Assumptions.assumeTrue(folder != null && Files.exists(folder.resolve(name)), "Archivo real no disponible: " + name);
        return Files.readAllBytes(folder.resolve(name));
    }

    private static List<KioskExcelIssueDto> byCode(KioskExcelParser.ParseResult r, String code) {
        return r.getIssues().stream().filter(i -> code.equals(i.getCode())).toList();
    }

    @Test
    void supervisionFormulaMatchesTheWorkbook() {
        // ((4002.28 + 250) * 2 * 14 / 12) / 37 kioscos = 268.1618... (valor de la hoja)
        assertThat(KioskSupervisionCost.compute(new BigDecimal("4002.28"), new BigDecimal("250"), 37))
                .isEqualByComparingTo("268.16");
        assertThat(KioskSupervisionCost.compute(null, new BigDecimal("250"), 37)).isNull();
        assertThat(KioskSupervisionCost.compute(new BigDecimal("4002.28"), new BigDecimal("250"), 0)).isNull();
    }

    @Test
    void aprilFileIsReadFromTheLatestSalesSheetAndMonthComesFromTheFileName() throws Exception {
        KioskExcelParser.ParseResult r = PARSER.parse("reporte de ventas abril.xlsx", read("reporte de ventas abril.xlsx"));

        assertThat(r.getFormat()).isEqualTo(KioskExcelParser.FORMAT_SHEET_YEAR);
        assertThat(r.getSheetName()).isEqualTo("ventas 2026");
        assertThat(r.getYear()).isEqualTo(2026);
        assertThat(r.getMonth()).isEqualTo(4);
        assertThat(r.getPeriodSource()).isEqualTo(KioskExcelParser.PERIOD_FROM_FILE_NAME);
        assertThat(r.isPeriodEditable()).isTrue();
        assertThat(r.getDays()).isEqualTo(30);
        // las columnas de resumen (venta del día, acumulado, año, diferencia) no son kioscos
        assertThat(r.getColumns()).hasSize(37);
        assertThat(r.getColumns().get(0).getExcelName()).isEqualTo("miraflores");
        assertThat(r.getColumns().get(0).getNormalized()).isEqualTo("MIRAFLORES");
        assertThat(r.getColumns()).extracting(KioskExcelParser.ParsedColumn::getNormalized)
                .doesNotContain("ACUMULADO", "DIFERENCIA", "VENTA DEL DIA");

        // 1-abr tiene venta; el 3-abr (viernes santo) está vacío para todos los kioscos
        KioskExcelDataDto.Day apr1 = r.getData().getDays().get(0);
        assertThat(apr1.getDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(apr1.getValues().get("miraflores")).isEqualByComparingTo("2392.5");
        KioskExcelDataDto.Day apr3 = r.getData().getDays().get(2);
        assertThat(apr3.getDate()).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(apr3.getValues().values()).allMatch(v -> v == null);

        // suma recalculada = fila TOTAL de la hoja
        assertThat(r.getRecomputedTotals().get("miraflores")).isEqualByComparingTo("77035.2");
        assertThat(r.getSheetTotals().get("miraflores")).isEqualByComparingTo("77035.2");
        assertThat(byCode(r, KioskExcelIssueDto.TOTAL_MISMATCH)).isEmpty();
        assertThat(r.getIssues().stream().filter(i -> KioskExcelIssueDto.BLOCKING.equals(i.getSeverity()))).isEmpty();

        assertThat(r.getData().getGoals().get("miraflores")).isEqualByComparingTo("110000");
    }

    @Test
    void ratesAreDerivedFromAmountsAndFixedCostsUseTheNewLabels() throws Exception {
        KioskExcelParser.ParseResult r = PARSER.parse("reporte de ventas abril.xlsx", read("reporte de ventas abril.xlsx"));

        KioskExcelDataDto.Rates miraflores = r.getData().getRates().get("miraflores");
        assertThat(miraflores.getProductCostPct()).isEqualByComparingTo("0.18");
        assertThat(miraflores.getSalesCommissionPct()).isEqualByComparingTo("0.04");
        assertThat(miraflores.getCardCommissionPct()).isEqualByComparingTo("0.0287");
        assertThat(miraflores.getTaxPct()).isEqualByComparingTo("0.025");
        // comisión de venta sólo aplica a Miraflores: en el resto el monto es 0 => tasa 0
        assertThat(r.getData().getRates().get("pradera concepción").getSalesCommissionPct()).isEqualByComparingTo("0");
        // sin ventas en el mes no se puede derivar la tasa
        assertThat(r.getData().getRates().get("pradera xela").getProductCostPct()).isNull();

        var costs = r.getData().getCosts().get("miraflores");
        assertThat(costs.keySet()).containsExactlyElementsOf(KioskExcelParser.COST_CODES);
        assertThat(costs.get("ALQUILER")).isEqualByComparingTo("14674.89");
        // "Salarios encargadas (MOD)" ocupa el lugar de MO indirecta; "Salarios suplentes (MOI)", el de MO directa
        assertThat(costs.get("SALARIOS_MO_INDIRECTA")).isEqualByComparingTo("4002.28");
        assertThat(costs.get("SALARIOS_MO_DIRECTA")).isEqualByComparingTo("500");
        assertThat(costs.get("SUPERVISION")).isEqualByComparingTo("268.16");
        assertThat(costs.get("BONO_14")).isEqualByComparingTo("263.86");
    }

    @Test
    void mayFileWithShiftedRowsIsReadByLabel() throws Exception {
        KioskExcelParser.ParseResult r = PARSER.parse("Reporte de ventas Mayo 2026.xlsx", read("Reporte de ventas Mayo 2026.xlsx"));

        assertThat(r.getYear()).isEqualTo(2026);
        assertThat(r.getMonth()).isEqualTo(5);
        assertThat(r.getDays()).isEqualTo(31);
        assertThat(r.getRecomputedTotals().get("miraflores")).isEqualByComparingTo("94283.4");
        assertThat(r.getData().getGoals().get("miraflores")).isEqualByComparingTo("120000");
        assertThat(r.getData().getCosts().get("miraflores").get("SALARIOS_MO_INDIRECTA")).isEqualByComparingTo("4002.28");
        assertThat(r.getData().getCosts().get("miraflores").get("SUPERVISION")).isEqualByComparingTo("268.16");
        assertThat(r.getData().getRates().get("miraflores").getSalesCommissionPct()).isEqualByComparingTo("0.04");
    }

    @Test
    void userCanOverrideTheMonthAndDatesFollowIt() throws Exception {
        KioskExcelParser.ParseResult r = PARSER.parse("reporte de ventas abril.xlsx", read("reporte de ventas abril.xlsx"),
                YearMonth.of(2026, 5));

        assertThat(r.getMonth()).isEqualTo(5);
        assertThat(r.getPeriodSource()).isEqualTo(KioskExcelParser.PERIOD_OVERRIDE);
        assertThat(r.getDays()).isEqualTo(31);
        assertThat(r.getData().getDays().get(0).getDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void legacyFormatStillParsesAndIgnoresPeriodOverrideAndSupervision() throws Exception {
        KioskExcelParser.ParseResult r = PARSER.parse("VENTAS ENERO 2025.xlsx", read("VENTAS ENERO 2025.xlsx"),
                YearMonth.of(2030, 1));

        assertThat(r.getFormat()).isEqualTo(KioskExcelParser.FORMAT_LEGACY);
        assertThat(r.isPeriodEditable()).isFalse();
        assertThat(r.getYear()).isEqualTo(2025);
        assertThat(r.getMonth()).isEqualTo(1);
        assertThat(r.getSheetName()).isEqualTo("Reporte de Vtas  orig.");
        // la supervisión no existe antes de 2026: queda vacía y no genera aviso de costos faltantes
        assertThat(r.getData().getCosts().get("MIRAFLORES").get("SUPERVISION")).isNull();
        assertThat(byCode(r, KioskExcelIssueDto.MISSING_COSTS).stream().map(KioskExcelIssueDto::getMessage))
                .noneMatch(m -> m.contains("SUPERVISION"));
    }
}
