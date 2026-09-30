package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KioskExcelParserTest {

    private static final String[] MONTH_NAMES = {"ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO",
            "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"};
    /** Kioscos (columnas) esperados por mes, enero..diciembre. */
    private static final int[] EXPECTED_KIOSKS = {36, 36, 36, 35, 36, 36, 38, 37, 36, 36, 36, 37};

    private static final KioskExcelParser PARSER = new KioskExcelParser();
    private static final Map<Integer, KioskExcelParser.ParseResult> RESULTS = new TreeMap<>();
    private static Path folder;

    @BeforeAll
    static void loadRealFiles() throws IOException, BusinessException {
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("kiosk.excel.dir");
        if (override != null) {
            candidates.add(Paths.get(override));
        }
        candidates.add(Paths.get("..", "Documentacion", "reportesventas"));
        candidates.add(Paths.get("C:\\Users\\eduar\\Desktop\\Work\\Fossiles\\Documentacion\\reportesventas"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                folder = candidate;
                break;
            }
        }
        if (folder == null) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .filter(p -> !p.getFileName().toString().startsWith("~$"))
                    .sorted().collect(Collectors.toList());
        }
        for (Path p : files) {
            String upper = p.getFileName().toString().toUpperCase(Locale.ROOT);
            if (!upper.contains("2025")) {
                continue; // los reportes de 2026 (formatos nuevos) se prueban en KioskExcelParserSheetYearTest
            }
            for (int i = 0; i < MONTH_NAMES.length; i++) {
                if (upper.contains(MONTH_NAMES[i])) {
                    RESULTS.put(i + 1, PARSER.parse(p.getFileName().toString(), Files.readAllBytes(p)));
                }
            }
        }
        if (System.getenv("KIOSK_EXCEL_REPORT") != null) {
            printReport();
        }
    }

    private static void requireFiles() {
        Assumptions.assumeTrue(folder != null && RESULTS.size() == 12,
                "Carpeta con los 12 Excel reales no disponible: se omite.");
    }

    // ------------------------------------------------------------------ archivos reales

    @Test
    void everyFileHasExpectedYearMonthAndKioskCount() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            assertThat(r.getYear()).as("a\u00F1o " + r.getFileName()).isEqualTo(2025);
            assertThat(r.getMonth()).as("mes " + r.getFileName()).isEqualTo(month);
            assertThat(r.getSheetName()).isEqualTo("Reporte de Vtas  orig.");
            assertThat(r.getColumns()).as("kioscos " + r.getFileName()).hasSize(EXPECTED_KIOSKS[month - 1]);
            assertThat(r.getDays()).as("días " + r.getFileName()).isEqualTo(java.time.YearMonth.of(2025, month).lengthOfMonth());
        }
    }

    @Test
    void jan1IsBlankForEveryKiosk() {
        requireFiles();
        KioskExcelDataDto.Day jan1 = RESULTS.get(1).getData().getDays().get(0);
        assertThat(jan1.getDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(jan1.getValues().values()).isNotEmpty().allMatch(v -> v == null);
    }

    /**
     * El archivo de agosto traía originalmente el texto "1254..6" en Plaza Cemaco (día 21); el dueño de los
     * archivos ya lo corrigió en origen en algunas copias. El test cubre ambos estados: con el texto sucio
     * se espera exactamente el descuadre conocido; con el dato ya corregido no debe haber ninguno.
     */
    @Test
    void salesTotalsMatchSheetTotalsExceptKnownCemacoCase() {
        requireFiles();
        boolean augustStillDirty = isAugustStillDirty();
        int mismatches = 0;
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            for (Map.Entry<String, BigDecimal> e : r.getSheetTotals().entrySet()) {
                BigDecimal diff = r.getRecomputedTotals().get(e.getKey()).subtract(e.getValue());
                if (diff.abs().compareTo(new BigDecimal("0.01")) > 0) {
                    mismatches++;
                    assertThat(month).isEqualTo(8);
                    assertThat(e.getKey()).isEqualTo("PLAZA CEMACO");
                    assertThat(diff.setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("1254.60");
                }
            }
            List<KioskExcelIssueDto> totalIssues = issues(r, KioskExcelIssueDto.TOTAL_MISMATCH);
            assertThat(totalIssues).hasSize(month == 8 && augustStillDirty ? 1 : 0);
        }
        assertThat(mismatches).isEqualTo(augustStillDirty ? 1 : 0);
    }

    private static boolean isAugustStillDirty() {
        return RESULTS.get(8).getIssues().stream()
                .anyMatch(i -> KioskExcelIssueDto.BLOCKING.equals(i.getSeverity()));
    }

    @Test
    void januaryControlTotalIsReadFromSheet() {
        requireFiles();
        KioskExcelParser.ParseResult r = RESULTS.get(1);
        BigDecimal total = r.getRecomputedTotals().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1090923.30");
    }

    @Test
    void blockingIssueExistsOnlyInAugust() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            List<KioskExcelIssueDto> blocking = RESULTS.get(month).getIssues().stream()
                    .filter(i -> KioskExcelIssueDto.BLOCKING.equals(i.getSeverity())).toList();
            if (month != 8 || !isAugustStillDirty()) {
                assertThat(blocking).as("BLOCKING en mes " + month).isEmpty();
            } else {
                assertThat(blocking).hasSize(1);
                KioskExcelIssueDto i = blocking.get(0);
                assertThat(i.getCode()).isEqualTo(KioskExcelIssueDto.NON_NUMERIC_CELL);
                assertThat(i.getExcelName()).isEqualTo("PLAZA CEMACO");
                assertThat(i.getDate()).isEqualTo(LocalDate.of(2025, 8, 21));
                assertThat(i.getCell()).isEqualTo("R29");
                assertThat(i.getRawValue()).isEqualTo("1254..6");
                assertThat(i.getSuggestion()).isEqualByComparingTo("1254.6");
                assertThat(RESULTS.get(8).getData().getBlockedCells()).hasSize(1);
                assertThat(RESULTS.get(8).getData().getBlockedCells().get(0).getIssueId()).isEqualTo(i.getId());
            }
        }
    }

    @Test
    void allFixedCostCategoriesAreFoundForAllKiosks() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            assertThat(r.getData().getCosts()).hasSize(r.getColumns().size());
            for (Map.Entry<String, Map<String, BigDecimal>> e : r.getData().getCosts().entrySet()) {
                assertThat(e.getValue().keySet()).as(r.getFileName() + " " + e.getKey())
                        .containsExactlyElementsOf(KioskExcelParser.COST_CODES);
            }
            // sin avisos de layout por filas no encontradas
            assertThat(issues(r, KioskExcelIssueDto.LAYOUT_ASSUMPTION))
                    .as("LAYOUT_ASSUMPTION " + r.getFileName()).isEmpty();
        }
    }

    @Test
    void missingCostsAndGoalOnlyForElQuicheInDecember() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            List<KioskExcelIssueDto> missingCosts = issues(r, KioskExcelIssueDto.MISSING_COSTS);
            List<KioskExcelIssueDto> missingGoal = issues(r, KioskExcelIssueDto.MISSING_GOAL);
            if (month == 12) {
                assertThat(missingCosts).extracting(KioskExcelIssueDto::getExcelName).containsExactly("EL QUICHE");
                assertThat(missingCosts.get(0).getMessage()).contains("ALQUILER").contains("LUZ").contains("TELEFONO_INTERNET_PROG");
                assertThat(missingGoal).extracting(KioskExcelIssueDto::getExcelName).containsExactly("EL QUICHE");
                assertThat(r.getData().getGoals().get("EL QUICHE")).isNotNull().isEqualByComparingTo("0");
            } else {
                assertThat(missingCosts).as("MISSING_COSTS " + r.getFileName()).isEmpty();
                assertThat(missingGoal).as("MISSING_GOAL " + r.getFileName()).isEmpty();
            }
        }
    }

    @Test
    void ratesAreReadFromTheRateRowsOfEachPair() {
        requireFiles();
        KioskExcelDataDto.Rates miraflores = RESULTS.get(1).getData().getRates().get("MIRAFLORES");
        assertThat(miraflores.getCardCommissionPct()).isEqualByComparingTo("0.025");
        assertThat(miraflores.getProductCostPct()).isEqualByComparingTo("0.18");
        assertThat(miraflores.getSalesCommissionPct()).isEqualByComparingTo("0.04");
        assertThat(miraflores.getTaxPct()).isEqualByComparingTo("0.025");
        assertThat(RESULTS.get(1).getData().getRates().get("PERI").getCardCommissionPct()).isEqualByComparingTo("0.02");
        assertThat(RESULTS.get(1).getData().getGoals().get("MIRAFLORES")).isEqualByComparingTo("130000");
        assertThat(RESULTS.get(1).getData().getCosts().get("MIRAFLORES").get("ALQUILER")).isEqualByComparingTo("14674.89");
        assertThat(RESULTS.get(1).getData().getCosts().get("MIRAFLORES").get("BONO_14")).isEqualByComparingTo("263.86");
        // ninguna tasa fuera de 0..1 ni nula en ning\u00FAn archivo
        for (int month = 1; month <= 12; month++) {
            for (Map.Entry<String, KioskExcelDataDto.Rates> e : RESULTS.get(month).getData().getRates().entrySet()) {
                KioskExcelDataDto.Rates r = e.getValue();
                for (BigDecimal v : List.of(r.getProductCostPct(), r.getSalesCommissionPct(), r.getCardCommissionPct(), r.getTaxPct())) {
                    assertThat(v).as(month + " " + e.getKey()).isNotNull();
                    assertThat(v).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                }
            }
        }
    }

    @Test
    void outliersAreInformationalOnlyAndThereAre11() {
        requireFiles();
        int total = 0;
        for (int month = 1; month <= 12; month++) {
            for (KioskExcelIssueDto i : issues(RESULTS.get(month), KioskExcelIssueDto.OUTLIER)) {
                assertThat(i.getSeverity()).isEqualTo(KioskExcelIssueDto.INFO);
                total++;
            }
        }
        assertThat(total).isEqualTo(11);
    }

    @Test
    void outOfMonthRowsAreIgnoredWithoutValues() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            assertThat(issues(r, KioskExcelIssueDto.OUT_OF_MONTH_VALUE)).isEmpty();
            assertThat(r.getData().getDays()).allMatch(d -> d.getDate().getMonthValue() == r.getMonth());
        }
    }

    @Test
    void noNegativeOrUnresolvedValuesOutsideAugust() {
        requireFiles();
        for (int month = 1; month <= 12; month++) {
            Optional<BigDecimal> negative = RESULTS.get(month).getData().getDays().stream()
                    .flatMap(d -> d.getValues().values().stream())
                    .filter(v -> v != null && v.signum() < 0).findAny();
            assertThat(negative).isEmpty();
        }
    }

    @Test
    void columnNamesAreKeptAndMatchAliasesAfterNormalization() {
        requireFiles();
        KioskExcelParser.ParseResult r = RESULTS.get(1);
        assertThat(r.getColumns()).extracting(KioskExcelParser.ParsedColumn::getExcelName).contains("MIRAFLORES", "PERI");
        assertThat(r.getColumns()).extracting(KioskExcelParser.ParsedColumn::getNormalized)
                .contains("MIRAFLORES").anyMatch(n -> n.startsWith("SANTAL"));
    }

    /** Alias sembrados por scripts/migration-kiosk-financials.sql (kioscos reales + históricos). */
    private static final java.util.Set<String> SEEDED_ALIASES = java.util.Set.of(
            "MIRAFLORES", "PERI", "ESKALA", "PORTALES", "METRONORTE", "NARANJO", "JUTIAPA", "CHIQUIMULA", "ESCUINTLA",
            "CHIMALTENANGO", "SANKRIS", "PACIFIC", "SANTA CLARA", "EL FRUTAL", "ATANASIO", "PLAZA CEMACO", "ZONA 4",
            "SAN LUCAS", "COBAN", "REU", "COATEPEQUE", "MAZATENANGO", "INTERPLAZA XELA", "UTZ ULEW", "VISTARES",
            "PRADERA XELA", "TIKAL FUTURA", "ANDARIA", "INTERPLAZA ESCUINTLA", "SANTALU", "SANTAL", "RUS MALL", "JALAPA",
            "PRADERA CONCEPCION", "METROCENTRO", "TELARES",
            "MAJADAS 11", "SANTA AMELIA", "CUERISIMOS", "EL QUICHE", "EL PARQUE COBAN");

    @Test
    void everyColumnNameOfTheYearMatchesASeededAlias() {
        requireFiles();
        java.util.Set<String> distinct = new java.util.TreeSet<>();
        for (int month = 1; month <= 12; month++) {
            for (KioskExcelParser.ParsedColumn c : RESULTS.get(month).getColumns()) {
                distinct.add(c.getNormalized());
            }
        }
        assertThat(SEEDED_ALIASES).containsAll(distinct);
    }

    // ------------------------------------------------------------------ unitarios puros

    @Test
    void normalizeAliasFollowsTheContract() {
        assertThat(KioskExcelParser.normalizeAlias("MIRAFLORES ")).isEqualTo("MIRAFLORES");
        assertThat(KioskExcelParser.normalizeAlias("SANTAL\u00D9")).isEqualTo("SANTALU");
        assertThat(KioskExcelParser.normalizeAlias("SANTAL\uFFFD")).isEqualTo("SANTAL");
        assertThat(KioskExcelParser.normalizeAlias("  Plaza   Cemaco ")).isEqualTo("PLAZA CEMACO");
        assertThat(KioskExcelParser.normalizeAlias("Telefono, Internet y programaci\uFFFDn"))
                .isEqualTo("TELEFONO INTERNET Y PROGRAMACIN");
        assertThat(KioskExcelParser.normalizeAlias("Bonificaci\uFFFDn")).startsWith("BONIFICACI");
        assertThat(KioskExcelParser.normalizeAlias("Zona 4")).isEqualTo("ZONA 4");
        assertThat(KioskExcelParser.normalizeAlias("Interplaza-Xela")).isEqualTo("INTERPLAZAXELA");
        assertThat(KioskExcelParser.normalizeAlias("\u00F1and\u00FA")).isEqualTo("NANDU");
        assertThat(KioskExcelParser.normalizeAlias(null)).isEmpty();
        assertThat(KioskExcelParser.normalizeAlias("\uFFFD")).isEmpty();
    }

    @Test
    void repairNumericTextFixesRepeatedSeparators() {
        assertThat(KioskExcelParser.repairNumericText("1254..6")).isEqualByComparingTo("1254.6");
        assertThat(KioskExcelParser.repairNumericText(" 1254 ..6 ")).isEqualByComparingTo("1254.6");
        assertThat(KioskExcelParser.repairNumericText("1,254.60")).isEqualByComparingTo("1254.60");
        assertThat(KioskExcelParser.repairNumericText("1254,6")).isEqualByComparingTo("1254.6");
        assertThat(KioskExcelParser.repairNumericText("1,254")).isEqualByComparingTo("1254");
        assertThat(KioskExcelParser.repairNumericText("Q 525.6")).isEqualByComparingTo("525.6");
        assertThat(KioskExcelParser.repairNumericText("525,,6")).isEqualByComparingTo("525.6");
    }

    @Test
    void repairNumericTextRejectsNonPlausibleValues() {
        assertThat(KioskExcelParser.repairNumericText("abc")).isNull();
        assertThat(KioskExcelParser.repairNumericText("12a4")).isNull();
        assertThat(KioskExcelParser.repairNumericText("")).isNull();
        assertThat(KioskExcelParser.repairNumericText(null)).isNull();
        assertThat(KioskExcelParser.repairNumericText("...")).isNull();
        assertThat(KioskExcelParser.repairNumericText("-5")).isNull();
        assertThat(KioskExcelParser.repairNumericText("1.2.3")).isNull();
        assertThat(KioskExcelParser.repairNumericText("99999999")).isNull();
    }

    @Test
    void garbageBytesAreReportedAsBusinessException() {
        try {
            PARSER.parse("~$VENTAS.xlsx", new byte[]{1, 2, 3});
            org.junit.jupiter.api.Assertions.fail("debía fallar");
        } catch (BusinessException e) {
            assertThat(e.getMessage()).contains("~$VENTAS.xlsx");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<KioskExcelIssueDto> issues(KioskExcelParser.ParseResult r, String code) {
        return r.getIssues().stream().filter(i -> code.equals(i.getCode())).toList();
    }

    private static void printReport() {
        StringBuilder sb = new StringBuilder("\n| archivo | a\u00F1o-mes | kioscos | ventas | hoja | BLOCKING | WARNING | INFO |\n");
        for (int month = 1; month <= 12; month++) {
            KioskExcelParser.ParseResult r = RESULTS.get(month);
            BigDecimal sales = r.getRecomputedTotals().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sheet = r.getSheetTotals().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            long b = r.getIssues().stream().filter(i -> "BLOCKING".equals(i.getSeverity())).count();
            long w = r.getIssues().stream().filter(i -> "WARNING".equals(i.getSeverity())).count();
            long inf = r.getIssues().stream().filter(i -> "INFO".equals(i.getSeverity())).count();
            sb.append(String.format("| %s | %d-%02d | %d | %s | %s | %d | %d | %d |%n", r.getFileName(), r.getYear(),
                    r.getMonth(), r.getColumns().size(), sales.setScale(2, java.math.RoundingMode.HALF_UP),
                    sheet.setScale(2, java.math.RoundingMode.HALF_UP), b, w, inf));
            for (KioskExcelIssueDto i : r.getIssues()) {
                if (!"INFO".equals(i.getSeverity()) || "LAYOUT_ASSUMPTION".equals(i.getCode())) {
                    sb.append("    ").append(i.getSeverity()).append(' ').append(i.getCode()).append(' ').append(i.getMessage()).append('\n');
                }
            }
        }
        System.out.println(sb);
    }
}
