package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parser sobre un Excel sintético (no depende de los archivos reales). */
class KioskExcelParserSyntheticTest {

    private final KioskExcelParser parser = new KioskExcelParser();

    @Test
    void parsesLayoutWithBlankColumnSpillRowsAndCorruptLabels() throws Exception {
        KioskExcelParser.ParseResult r = parser.parse("VENTAS FEBRERO 2025.xlsx",
                KioskExcelTestFixtures.februaryWorkbook(null, false, null));

        assertThat(r.getYear()).isEqualTo(2025);
        assertThat(r.getMonth()).isEqualTo(2);
        assertThat(r.getSheetName()).isEqualTo("Reporte de Vtas  orig.");
        assertThat(r.getColumns()).extracting(KioskExcelParser.ParsedColumn::getExcelName)
                .containsExactly("MIRAFLORES", "PERI", "NUEVO KIOSCO");
        assertThat(r.getDays()).isEqualTo(28);
        assertThat(r.getData().getDays()).hasSize(28);
        // 1-feb vacío => null (sin fila); el resto trae valor
        assertThat(r.getData().getDays().get(0).getValues().get("MIRAFLORES")).isNull();
        assertThat(r.getData().getDays().get(1).getValues().get("MIRAFLORES")).isEqualByComparingTo("100");
        assertThat(r.getSalesCells()).isEqualTo(27 * 3);
        assertThat(r.getRecomputedTotals().get("MIRAFLORES")).isEqualByComparingTo("2700");
        assertThat(r.getSheetTotals().get("PERI")).isEqualByComparingTo("1350");
        assertThat(r.getIssues().stream().filter(i -> !KioskExcelIssueDto.WARNING.equals(i.getSeverity())
                && !KioskExcelIssueDto.INFO.equals(i.getSeverity()))).isEmpty();

        // metas y tasas: sólo la fila de tasa, no la calculada de debajo (777)
        assertThat(r.getData().getGoals().get("MIRAFLORES")).isEqualByComparingTo("130000");
        KioskExcelDataDto.Rates peri = r.getData().getRates().get("PERI");
        assertThat(peri.getProductCostPct()).isEqualByComparingTo("0.18");
        assertThat(peri.getSalesCommissionPct()).isEqualByComparingTo("0.04");
        assertThat(peri.getCardCommissionPct()).isEqualByComparingTo("0.02");
        assertThat(peri.getTaxPct()).isEqualByComparingTo("0.025");

        // 10 categorías por prefijo (incluye etiquetas con U+FFFD); Total CI (fijos) no se importa
        assertThat(r.getData().getCosts().get("MIRAFLORES").keySet()).containsExactlyElementsOf(KioskExcelParser.COST_CODES);
        assertThat(r.getData().getCosts().get("MIRAFLORES").get("TELEFONO_INTERNET_PROG")).isEqualByComparingTo("1002");
        assertThat(r.getData().getCosts().get("MIRAFLORES").get("BONIFICACION")).isEqualByComparingTo("1005");
        assertThat(r.getData().getCosts().get("PERI").get("SALARIOS_MO_DIRECTA")).isEqualByComparingTo("2009");
        // NUEVO KIOSCO sin alquiler ni luz => null + aviso de costos y de meta 0
        assertThat(r.getData().getCosts().get("NUEVO KIOSCO").get("ALQUILER")).isNull();
        assertThat(r.getData().getCosts().get("NUEVO KIOSCO").get("LUZ")).isNull();
        assertThat(r.getData().getGoals().get("NUEVO KIOSCO")).isEqualByComparingTo("0");
        assertThat(codes(r, KioskExcelIssueDto.MISSING_COSTS)).hasSize(1);
        assertThat(codes(r, KioskExcelIssueDto.MISSING_GOAL)).hasSize(1);
        assertThat(codes(r, KioskExcelIssueDto.TOTAL_MISMATCH)).isEmpty();
        assertThat(codes(r, KioskExcelIssueDto.OUT_OF_MONTH_VALUE)).isEmpty();
    }

    @Test
    void spillRowWithValueRaisesOutOfMonthWarningAndIsIgnored() throws Exception {
        KioskExcelParser.ParseResult r = parser.parse("VENTAS FEBRERO 2025.xlsx",
                KioskExcelTestFixtures.februaryWorkbook(null, false, 999.0));
        List<KioskExcelIssueDto> out = codes(r, KioskExcelIssueDto.OUT_OF_MONTH_VALUE);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getDate()).isEqualTo(LocalDate.of(2025, 3, 2));
        assertThat(out.get(0).getExcelName()).isEqualTo("MIRAFLORES");
        assertThat(r.getData().getDays()).allMatch(d -> d.getDate().getMonthValue() == 2);
        assertThat(r.getRecomputedTotals().get("MIRAFLORES")).isEqualByComparingTo("2700");
    }

    @Test
    void textCellIsBlockingWithSuggestionAndTotalMismatchWarning() throws Exception {
        KioskExcelParser.ParseResult r = parser.parse("VENTAS FEBRERO 2025.xlsx",
                KioskExcelTestFixtures.februaryWorkbook("150..5", false, null));
        List<KioskExcelIssueDto> blocking = r.getIssues().stream()
                .filter(i -> KioskExcelIssueDto.BLOCKING.equals(i.getSeverity())).toList();
        assertThat(blocking).hasSize(1);
        KioskExcelIssueDto issue = blocking.get(0);
        assertThat(issue.getCode()).isEqualTo(KioskExcelIssueDto.NON_NUMERIC_CELL);
        assertThat(issue.getExcelName()).isEqualTo("PERI");
        assertThat(issue.getDate()).isEqualTo(LocalDate.of(2025, 2, 10));
        assertThat(issue.getCell()).isEqualTo("D18");
        assertThat(issue.getRawValue()).isEqualTo("150..5");
        assertThat(issue.getSuggestion()).isEqualByComparingTo("150.5");
        assertThat(r.getData().getBlockedCells()).hasSize(1);
        assertThat(r.getData().getBlockedCells().get(0).getIssueId()).isEqualTo(issue.getId());
        // el valor de la celda queda null en data (no se importa hasta resolver)
        assertThat(r.getData().getDays().get(9).getValues().get("PERI")).isNull();
        assertThat(codes(r, KioskExcelIssueDto.TOTAL_MISMATCH)).hasSize(1);
    }

    @Test
    void negativeValueIsBlockingAndKeptInData() throws Exception {
        KioskExcelParser.ParseResult r = parser.parse("VENTAS FEBRERO 2025.xlsx",
                KioskExcelTestFixtures.februaryWorkbook(null, true, null));
        List<KioskExcelIssueDto> negs = codes(r, KioskExcelIssueDto.NEGATIVE_VALUE);
        assertThat(negs).hasSize(1);
        assertThat(negs.get(0).getSeverity()).isEqualTo(KioskExcelIssueDto.BLOCKING);
        assertThat(r.getData().getDays().get(10).getValues().get("PERI")).isEqualByComparingTo(new BigDecimal("-5"));
        assertThat(r.getData().getBlockedCells()).extracting(KioskExcelDataDto.BlockedCell::getCode)
                .containsExactly(KioskExcelIssueDto.NEGATIVE_VALUE);
    }

    private static List<KioskExcelIssueDto> codes(KioskExcelParser.ParseResult r, String code) {
        return r.getIssues().stream().filter(i -> code.equals(i.getCode())).toList();
    }
}
