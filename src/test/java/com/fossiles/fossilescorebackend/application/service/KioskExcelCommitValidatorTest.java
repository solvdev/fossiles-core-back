package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExcelCommitRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KioskExcelCommitValidatorTest {

    private static final String SHA = "a".repeat(64);

    private final Map<String, Long> aliases = Map.of("MIRAFLORES", 1L, "PERI", 2L);
    private final KioskExcelCommitValidator validator = new KioskExcelCommitValidator(
            aliases::get, id -> id != null && id >= 1 && id <= 50, Set.copyOf(KioskExcelParser.COST_CODES));

    // ------------------------------------------------------------------ helpers

    private static Map<String, BigDecimal> values(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v == null ? null : new BigDecimal(v.toString()));
        }
        return m;
    }

    private static KioskExcelDataDto.Day day(int d, Map<String, BigDecimal> values) {
        return KioskExcelDataDto.Day.builder().date(LocalDate.of(2025, 8, d)).values(values).build();
    }

    private static KioskExcelDataDto baseData() {
        List<KioskExcelDataDto.Day> days = new ArrayList<>();
        days.add(day(1, values("MIRAFLORES", null, "PERI", null)));
        days.add(day(2, values("MIRAFLORES", "100.5", "PERI", "0")));
        days.add(day(3, values("MIRAFLORES", "200", "PERI", null)));
        Map<String, KioskExcelDataDto.Rates> rates = new LinkedHashMap<>();
        rates.put("MIRAFLORES", KioskExcelDataDto.Rates.builder().productCostPct(new BigDecimal("0.18"))
                .salesCommissionPct(new BigDecimal("0.04")).cardCommissionPct(new BigDecimal("0.025"))
                .taxPct(new BigDecimal("0.025")).build());
        Map<String, Map<String, BigDecimal>> costs = new LinkedHashMap<>();
        Map<String, BigDecimal> c = new LinkedHashMap<>();
        c.put("ALQUILER", new BigDecimal("14674.89"));
        c.put("LUZ", null);
        costs.put("MIRAFLORES", c);
        Map<String, BigDecimal> goals = new LinkedHashMap<>();
        goals.put("MIRAFLORES", new BigDecimal("130000"));
        return KioskExcelDataDto.builder().days(days).goals(goals).rates(rates).costs(costs)
                .blockedCells(new ArrayList<>()).build();
    }

    private static KioskExcelCommitRequest.FileCommit file(KioskExcelDataDto data) {
        return KioskExcelCommitRequest.FileCommit.builder().fileName("VENTAS AGOSTO 2025.xlsx").sha256(SHA)
                .year(2025).month(8).siteMapping(new LinkedHashMap<>()).resolutions(new LinkedHashMap<>())
                .data(data).build();
    }

    private KioskExcelCommitValidator.Result validate(KioskExcelCommitRequest.FileCommit... files) {
        return validator.validate(KioskExcelCommitRequest.builder().replaceExisting(true).files(List.of(files)).build());
    }

    private static KioskExcelDataDto.BlockedCell blockedCemaco(KioskExcelDataDto data, String id) {
        KioskExcelDataDto.BlockedCell b = KioskExcelDataDto.BlockedCell.builder().issueId(id)
                .code("NON_NUMERIC_CELL").excelName("MIRAFLORES").date(LocalDate.of(2025, 8, 3)).cell("C11")
                .rawValue("1254..6").build();
        data.getBlockedCells().add(b);
        return b;
    }

    // ------------------------------------------------------------------ casos válidos

    @Test
    void validPayloadIsNormalized() {
        KioskExcelCommitValidator.Result r = validate(file(baseData()));
        assertThat(r.problems).isEmpty();
        KioskExcelCommitValidator.ResolvedFile f = r.files.get(0);
        assertThat(f.targets.get("MIRAFLORES").siteId).isEqualTo(1L);
        assertThat(f.targets.get("PERI").siteId).isEqualTo(2L);
        // null = sin fila; 0 = fila con 0
        assertThat(f.sales.get("MIRAFLORES")).containsOnlyKeys(LocalDate.of(2025, 8, 2), LocalDate.of(2025, 8, 3));
        assertThat(f.sales.get("PERI")).containsOnlyKeys(LocalDate.of(2025, 8, 2));
        assertThat(f.sales.get("PERI").get(LocalDate.of(2025, 8, 2))).isEqualByComparingTo("0");
        assertThat(f.costs.get("MIRAFLORES")).containsOnlyKeys("ALQUILER");
    }

    @Test
    void numericResolutionReplacesTheBlockedCell() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(2).getValues().put("MIRAFLORES", null);
        blockedCemaco(data, "i1");
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getResolutions().put("i1", 1254.6);
        KioskExcelCommitValidator.Result r = validate(f);
        assertThat(r.problems).isEmpty();
        assertThat(r.files.get(0).sales.get("MIRAFLORES").get(LocalDate.of(2025, 8, 3))).isEqualByComparingTo("1254.6");
    }

    @Test
    void ignoreResolutionLeavesTheCellWithoutData() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(2).getValues().put("MIRAFLORES", null);
        blockedCemaco(data, "i1");
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getResolutions().put("i1", "IGNORE");
        KioskExcelCommitValidator.Result r = validate(f);
        assertThat(r.problems).isEmpty();
        assertThat(r.files.get(0).sales.get("MIRAFLORES")).containsOnlyKeys(LocalDate.of(2025, 8, 2));
    }

    @Test
    void manualMappingAndCreateAreAccepted() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("NUEVO KIOSCO", new BigDecimal("55"));
        data.getDays().get(1).getValues().put("OTRO", new BigDecimal("5"));
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getSiteMapping().put("NUEVO KIOSCO", KioskExcelCommitRequest.SiteMappingEntry.builder()
                .create(KioskExcelCommitRequest.CreateSite.builder().name("Nuevo Kiosco").build()).build());
        f.getSiteMapping().put("OTRO", KioskExcelCommitRequest.SiteMappingEntry.builder().siteId(7L).build());
        KioskExcelCommitValidator.Result r = validate(f);
        assertThat(r.problems).isEmpty();
        KioskExcelCommitValidator.ResolvedFile rf = r.files.get(0);
        assertThat(rf.targets.get("NUEVO KIOSCO").create.getName()).isEqualTo("Nuevo Kiosco");
        assertThat(rf.targets.get("NUEVO KIOSCO").create.getStatus()).isEqualTo("CLOSED");
        assertThat(rf.targets.get("OTRO").siteId).isEqualTo(7L);
        assertThat(rf.targets.get("OTRO").manual).isTrue();
    }

    // ------------------------------------------------------------------ rechazos

    @Test
    void unresolvedBlockingCellIsRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(2).getValues().put("MIRAFLORES", null);
        blockedCemaco(data, "i1");
        KioskExcelCommitValidator.Result r = validate(file(data));
        assertThat(r.problems).anyMatch(p -> p.contains("falta resolver") && p.contains("1254..6"));
        assertThatThrownBy(r::throwIfInvalid).isInstanceOf(BusinessException.class)
                .hasMessageContaining("rechazada").hasMessageContaining("1254..6");
    }

    @Test
    void invalidResolutionsAreRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(2).getValues().put("MIRAFLORES", null);
        blockedCemaco(data, "i1");
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getResolutions().put("i1", "abc");
        f.getResolutions().put("i99", 10);
        KioskExcelCommitValidator.Result r = validate(f);
        assertThat(r.problems).anyMatch(p -> p.contains("'i1'") && p.contains("número"));
        assertThat(r.problems).anyMatch(p -> p.contains("'i99'"));

        f.getResolutions().clear();
        f.getResolutions().put("i1", -3);
        assertThat(validate(f).problems).anyMatch(p -> p.contains("'i1'") && p.contains("entre 0"));
        f.getResolutions().put("i1", 1_000_000);
        assertThat(validate(f).problems).anyMatch(p -> p.contains("'i1'"));
    }

    @Test
    void negativeSalesAreRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("MIRAFLORES", new BigDecimal("-1"));
        assertThat(validate(file(data)).problems).anyMatch(p -> p.contains("negativa"));
    }

    @Test
    void negativeResolvedByIgnoreOrNumberIsAccepted() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("MIRAFLORES", new BigDecimal("-1"));
        data.getBlockedCells().add(KioskExcelDataDto.BlockedCell.builder().issueId("i5").code("NEGATIVE_VALUE")
                .excelName("MIRAFLORES").date(LocalDate.of(2025, 8, 2)).cell("C10").rawValue("-1").build());
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getResolutions().put("i5", 1);
        KioskExcelCommitValidator.Result r = validate(f);
        assertThat(r.problems).isEmpty();
        assertThat(r.files.get(0).sales.get("MIRAFLORES").get(LocalDate.of(2025, 8, 2))).isEqualByComparingTo("1");
    }

    @Test
    void amountsOverOneMillionAreRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("MIRAFLORES", new BigDecimal("1000000"));
        data.getCosts().get("MIRAFLORES").put("LUZ", new BigDecimal("2000000"));
        data.getGoals().put("MIRAFLORES", new BigDecimal("5000000"));
        List<String> problems = validate(file(data)).problems;
        assertThat(problems).anyMatch(p -> p.contains("supera"));
        assertThat(problems).anyMatch(p -> p.contains("LUZ"));
        assertThat(problems).anyMatch(p -> p.contains("meta"));
    }

    @Test
    void ratesOutsideZeroToOneAreRejected() {
        KioskExcelDataDto data = baseData();
        data.getRates().get("MIRAFLORES").setCardCommissionPct(new BigDecimal("2.5"));
        data.getRates().get("MIRAFLORES").setTaxPct(new BigDecimal("-0.1"));
        List<String> problems = validate(file(data)).problems;
        assertThat(problems).anyMatch(p -> p.contains("tarjeta"));
        assertThat(problems).anyMatch(p -> p.contains("IVA"));
        data.getRates().get("MIRAFLORES").setCardCommissionPct(BigDecimal.ONE);
        data.getRates().get("MIRAFLORES").setTaxPct(BigDecimal.ZERO);
        assertThat(validate(file(data)).problems).isEmpty();
    }

    @Test
    void unmappedColumnIsRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("DESCONOCIDO", new BigDecimal("5"));
        assertThat(validate(file(data)).problems).anyMatch(p -> p.contains("DESCONOCIDO") && p.contains("no tiene sitio"));
    }

    @Test
    void mappingToMissingSiteOrDuplicatedSiteIsRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("X", new BigDecimal("5"));
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getSiteMapping().put("X", KioskExcelCommitRequest.SiteMappingEntry.builder().siteId(999L).build());
        assertThat(validate(f).problems).anyMatch(p -> p.contains("999") && p.contains("no existe"));

        f.getSiteMapping().put("X", KioskExcelCommitRequest.SiteMappingEntry.builder().siteId(1L).build());
        assertThat(validate(f).problems).anyMatch(p -> p.contains("mismo sitio"));
    }

    @Test
    void invalidNewSiteIsRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().get(1).getValues().put("NUEVO", new BigDecimal("5"));
        KioskExcelCommitRequest.FileCommit f = file(data);
        f.getSiteMapping().put("NUEVO", KioskExcelCommitRequest.SiteMappingEntry.builder()
                .create(KioskExcelCommitRequest.CreateSite.builder().name("  ").build()).build());
        assertThat(validate(f).problems).anyMatch(p -> p.contains("nombre de sitio nuevo"));
        f.getSiteMapping().put("NUEVO", KioskExcelCommitRequest.SiteMappingEntry.builder()
                .create(KioskExcelCommitRequest.CreateSite.builder().name("Z").status("OTRO").build()).build());
        assertThat(validate(f).problems).anyMatch(p -> p.contains("estado de sitio"));
    }

    @Test
    void daysOutsideTheDeclaredMonthAreRejected() {
        KioskExcelDataDto data = baseData();
        data.getDays().add(KioskExcelDataDto.Day.builder().date(LocalDate.of(2025, 9, 1))
                .values(values("MIRAFLORES", "5")).build());
        assertThat(validate(file(data)).problems).anyMatch(p -> p.contains("no pertenece"));

        KioskExcelDataDto dup = baseData();
        dup.getDays().add(day(2, values("MIRAFLORES", "5")));
        assertThat(validate(file(dup)).problems).anyMatch(p -> p.contains("repetida"));
    }

    @Test
    void unknownCostCategoryIsRejected() {
        KioskExcelDataDto data = baseData();
        data.getCosts().get("MIRAFLORES").put("INVENTADA", new BigDecimal("5"));
        assertThat(validate(file(data)).problems).anyMatch(p -> p.contains("INVENTADA"));
    }

    @Test
    void structuralProblemsAreRejected() {
        assertThat(validator.validate(null).problems).isNotEmpty();
        assertThat(validator.validate(KioskExcelCommitRequest.builder().files(List.of()).build()).problems).isNotEmpty();

        KioskExcelCommitRequest.FileCommit bad = file(baseData());
        bad.setSha256("xyz");
        bad.setMonth(13);
        bad.setYear(1900);
        List<String> problems = validate(bad).problems;
        assertThat(problems).anyMatch(p -> p.contains("sha256"));
        assertThat(problems).anyMatch(p -> p.contains("mes inválido"));
        assertThat(problems).anyMatch(p -> p.contains("año inválido"));

        KioskExcelCommitRequest.FileCommit noData = file(null);
        assertThat(validate(noData).problems).anyMatch(p -> p.contains("faltan los datos"));
    }

    @Test
    void twoFilesForTheSameMonthAreRejected() {
        KioskExcelCommitValidator.Result r = validate(file(baseData()), file(baseData()));
        assertThat(r.problems).anyMatch(p -> p.contains("más de un archivo"));
    }
}
