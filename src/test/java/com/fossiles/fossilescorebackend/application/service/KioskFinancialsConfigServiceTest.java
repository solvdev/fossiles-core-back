package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigCopyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsConfigResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCopyResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSiteResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskSiteAliasNormalizer;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class KioskFinancialsConfigServiceTest {

    @Mock
    private KioskFinancialsAccessGuard guard;
    @Mock
    private KioskSiteRepository siteRepository;
    @Mock
    private KioskSiteAliasRepository aliasRepository;
    @Mock
    private KioskCostCategoryRepository categoryRepository;
    @Mock
    private KioskFixedCostRepository fixedCostRepository;
    @Mock
    private KioskPeriodConfigRepository configRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private KioskSalesSourceResolver resolver;
    @InjectMocks
    private KioskFinancialsConfigService service;

    private KioskSiteEntity site1;

    @BeforeEach
    void setUp() {
        site1 = KioskSiteEntity.builder().id(1L).name("MIRAFLORES II").locationId(15L).status("ACTIVE").build();
        lenient().when(siteRepository.findById(1L)).thenReturn(Optional.of(site1));
        lenient().when(siteRepository.findAllById(anyCollection())).thenReturn(List.of(site1));
        lenient().when(siteRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(site1));
        lenient().when(siteRepository.save(any(KioskSiteEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                category("ALQUILER", 1), category("LUZ", 2)));
        lenient().when(guard.currentUserId()).thenReturn(7L);
    }

    private static KioskCostCategoryEntity category(String code, int order) {
        return KioskCostCategoryEntity.builder().code(code).name(code).sortOrder(order).active(true).build();
    }

    private static KioskFixedCostEntity cost(long siteId, int year, int month, String code, String amount) {
        return KioskFixedCostEntity.builder().siteId(siteId).periodYear(year).periodMonth(month)
                .categoryCode(code).amount(new BigDecimal(amount)).build();
    }

    private static KioskFinancialsConfigBulkRequest bulk(int year, KioskFinancialsConfigBulkRequest.Change... changes) {
        return KioskFinancialsConfigBulkRequest.builder().year(year).changes(List.of(changes)).build();
    }

    private List<Object> savedConfigs() {
        ArgumentCaptor<Iterable> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(configRepository).saveAll(captor.capture());
        List<Object> out = new ArrayList<>();
        captor.getValue().forEach(out::add);
        return out;
    }

    private List<KioskFixedCostEntity> savedCosts() {
        ArgumentCaptor<Iterable> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(fixedCostRepository).saveAll(captor.capture());
        List<KioskFixedCostEntity> out = new ArrayList<>();
        captor.getValue().forEach(o -> out.add((KioskFixedCostEntity) o));
        return out;
    }

    // ------------------------------------------------------------------ bulk

    @Test
    void bulkTouchesOnlyPresentKeysAndNullCostDeletesRow() throws Exception {
        KioskPeriodConfigEntity existing = KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2026).periodMonth(3)
                .salesGoal(new BigDecimal("100000.00")).productCostPct(new BigDecimal("0.1800"))
                .salesCommissionPct(new BigDecimal("0.0400")).source("EXCEL").build();
        KioskFixedCostEntity luz = cost(1, 2026, 3, "LUZ", "20.00");
        lenient().when(configRepository.findByPeriodYear(2026)).thenReturn(List.of(existing));
        lenient().when(fixedCostRepository.findByPeriodYear(2026))
                .thenReturn(List.of(cost(1, 2026, 3, "ALQUILER", "10.00"), luz));

        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        costs.put("ALQUILER", new BigDecimal("15000"));
        costs.put("LUZ", null); // null borra
        KioskFinancialsBulkResponse response = service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(3)
                .productCostPct(Optional.of(new BigDecimal("0.20")))  // presente
                // goal ausente (null) => no se toca
                .costs(costs).build()));

        assertThat(response.getUpdatedMonths()).isEqualTo(1);
        assertThat(response.getUpdatedCells()).isEqualTo(3);

        KioskPeriodConfigEntity saved = (KioskPeriodConfigEntity) savedConfigs().get(0);
        assertThat(saved.getSalesGoal()).isEqualByComparingTo("100000.00");        // intacta
        assertThat(saved.getSalesCommissionPct()).isEqualByComparingTo("0.0400"); // intacta
        assertThat(saved.getProductCostPct()).isEqualByComparingTo("0.2000");
        assertThat(saved.getSource()).isEqualTo("MANUAL");
        assertThat(saved.getUpdatedBy()).isEqualTo(7L);

        List<KioskFixedCostEntity> savedCosts = savedCosts();
        assertThat(savedCosts).hasSize(1);
        assertThat(savedCosts.get(0).getCategoryCode()).isEqualTo("ALQUILER");
        assertThat(savedCosts.get(0).getAmount()).isEqualByComparingTo("15000.00");
        verify(fixedCostRepository).deleteAll(List.of(luz));
    }

    @Test
    void bulkExplicitNullOnGoalClearsIt() throws Exception {
        KioskPeriodConfigEntity existing = KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2026).periodMonth(3)
                .salesGoal(new BigDecimal("100000.00")).build();
        lenient().when(configRepository.findByPeriodYear(2026)).thenReturn(List.of(existing));

        service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(3).goal(Optional.empty()).build()));

        assertThat(((KioskPeriodConfigEntity) savedConfigs().get(0)).getSalesGoal()).isNull();
        verify(fixedCostRepository, never()).saveAll(any());
        verify(fixedCostRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void bulkCostsOnlyDoesNotCreateConfigRow() throws Exception {
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        costs.put("ALQUILER", new BigDecimal("15000"));
        costs.put("LUZ", BigDecimal.ZERO);

        KioskFinancialsBulkResponse response = service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(4).costs(costs).build()));

        assertThat(response.getUpdatedCells()).isEqualTo(2);
        verify(configRepository, never()).saveAll(any());
        assertThat(savedCosts()).extracting(KioskFixedCostEntity::getCategoryCode).containsExactly("ALQUILER", "LUZ");
    }

    @Test
    void bulkCreatesConfigRowWhenMissing() throws Exception {
        service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(5).goal(Optional.of(new BigDecimal("120000")))
                .taxPct(Optional.of(new BigDecimal("0.025"))).build()));

        KioskPeriodConfigEntity saved = (KioskPeriodConfigEntity) savedConfigs().get(0);
        assertThat(saved.getSiteId()).isEqualTo(1L);
        assertThat(saved.getPeriodYear()).isEqualTo(2026);
        assertThat(saved.getPeriodMonth()).isEqualTo(5);
        assertThat(saved.getSalesGoal()).isEqualByComparingTo("120000.00");
        assertThat(saved.getTaxPct()).isEqualByComparingTo("0.0250");
        assertThat(saved.getProductCostPct()).isNull();
    }

    @Test
    void bulkRejectsInvalidValues() {
        Map<String, BigDecimal> unknown = new HashMap<>();
        unknown.put("NO_EXISTE", BigDecimal.ONE);
        Map<String, BigDecimal> negative = new HashMap<>();
        negative.put("LUZ", new BigDecimal("-1"));

        assertThatThrownBy(() -> service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(1).costs(unknown).build()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(1).costs(negative).build()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(1).productCostPct(Optional.of(new BigDecimal("18"))).build())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(1L).month(13).build()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bulkUpdate(bulk(2026, KioskFinancialsConfigBulkRequest.Change.builder()
                .siteId(99L).month(1).build()))).isInstanceOf(BusinessException.class);
        verify(configRepository, never()).saveAll(any());
        verify(fixedCostRepository, never()).saveAll(any());
    }

    @Test
    void bulkRequestJsonDistinguishesAbsentFromExplicitNull() throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        String json = "{\"year\":2026,\"changes\":[{\"siteId\":1,\"month\":3,\"goal\":null,"
                + "\"productCostPct\":0.18,\"costs\":{\"ALQUILER\":15000,\"LUZ\":null}}]}";

        KioskFinancialsConfigBulkRequest req = mapper.readValue(json, KioskFinancialsConfigBulkRequest.class);
        KioskFinancialsConfigBulkRequest.Change c = req.getChanges().get(0);

        assertThat(c.getGoal()).isNotNull();
        assertThat(c.getGoal()).isEmpty();                       // clave presente con null
        assertThat(c.getProductCostPct()).contains(new BigDecimal("0.18"));
        assertThat(c.getSalesCommissionPct()).isNull();          // clave ausente
        assertThat(c.getCosts()).containsKey("LUZ");
        assertThat(c.getCosts().get("LUZ")).isNull();
        assertThat(c.getCosts().get("ALQUILER")).isEqualByComparingTo("15000");
    }

    // ------------------------------------------------------------------ copy

    private void stubCopyData() {
        KioskPeriodConfigEntity src = KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2025).periodMonth(12)
                .salesGoal(new BigDecimal("100.00")).productCostPct(new BigDecimal("0.1800"))
                .salesCommissionPct(new BigDecimal("0.0400")).source("EXCEL").build();
        KioskPeriodConfigEntity janTarget = KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2026).periodMonth(1)
                .salesGoal(new BigDecimal("500.00")).source("MANUAL").build();
        lenient().when(configRepository.findByPeriodYear(2025)).thenReturn(List.of(src));
        lenient().when(configRepository.findByPeriodYear(2026)).thenReturn(List.of(janTarget));
        lenient().when(fixedCostRepository.findByPeriodYear(2025)).thenReturn(List.of(
                cost(1, 2025, 12, "ALQUILER", "10.00"), cost(1, 2025, 12, "LUZ", "20.00")));
        lenient().when(fixedCostRepository.findByPeriodYear(2026))
                .thenReturn(List.of(cost(1, 2026, 1, "ALQUILER", "999.00")));
    }

    private KioskFinancialsConfigCopyRequest copyRequest(boolean overwrite, String... include) {
        return KioskFinancialsConfigCopyRequest.builder()
                .fromYear(2025).fromMonth(12).toYear(2026).toMonths(List.of(1, 2))
                .siteIds(null).include(include.length == 0 ? null : List.of(include)).overwrite(overwrite).build();
    }

    @Test
    void copyWithoutOverwriteKeepsFilledCells() throws Exception {
        stubCopyData();

        KioskFinancialsCopyResponse r = service.copy(copyRequest(false));

        // Enero: meta omitida (1), tasas copiadas (2), ALQUILER omitido (1), LUZ copiado (1) => 3 copiadas / 2 omitidas
        // Febrero: meta + 2 tasas + 2 costos => 5 copiadas
        assertThat(r.getCopiedMonths()).isEqualTo(2);
        assertThat(r.getCopiedCells()).isEqualTo(8);
        assertThat(r.getSkippedCells()).isEqualTo(2);

        List<Object> configs = savedConfigs();
        assertThat(configs).hasSize(2);
        KioskPeriodConfigEntity jan = (KioskPeriodConfigEntity) configs.stream()
                .filter(o -> ((KioskPeriodConfigEntity) o).getPeriodMonth() == 1).findFirst().orElseThrow();
        assertThat(jan.getSalesGoal()).isEqualByComparingTo("500.00"); // no se piso
        assertThat(jan.getProductCostPct()).isEqualByComparingTo("0.1800");
        assertThat(jan.getSource()).isEqualTo("COPIED");

        Map<String, KioskFixedCostEntity> janCosts = new HashMap<>();
        for (KioskFixedCostEntity fc : savedCosts()) {
            if (fc.getPeriodMonth() == 1) {
                janCosts.put(fc.getCategoryCode(), fc);
            }
        }
        assertThat(janCosts).containsOnlyKeys("LUZ"); // ALQUILER no se guarda: ya estaba lleno
        assertThat(janCosts.get("LUZ").getAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void copyWithOverwriteReplacesFilledCells() throws Exception {
        stubCopyData();

        KioskFinancialsCopyResponse r = service.copy(copyRequest(true));

        assertThat(r.getSkippedCells()).isZero();
        assertThat(r.getCopiedCells()).isEqualTo(10);
        KioskPeriodConfigEntity jan = (KioskPeriodConfigEntity) savedConfigs().stream()
                .filter(o -> ((KioskPeriodConfigEntity) o).getPeriodMonth() == 1).findFirst().orElseThrow();
        assertThat(jan.getSalesGoal()).isEqualByComparingTo("100.00");
        assertThat(savedCosts().stream()
                .filter(c -> c.getPeriodMonth() == 1 && c.getCategoryCode().equals("ALQUILER")).findFirst().orElseThrow()
                .getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void copyOnlyCostsLeavesConfigUntouched() throws Exception {
        stubCopyData();

        KioskFinancialsCopyResponse r = service.copy(copyRequest(false, "COSTS"));

        assertThat(r.getCopiedCells()).isEqualTo(3); // ene: LUZ; feb: ALQUILER + LUZ
        verify(configRepository, never()).saveAll(any());
    }

    @Test
    void copyValidatesInput() {
        assertThatThrownBy(() -> service.copy(KioskFinancialsConfigCopyRequest.builder()
                .fromYear(2025).fromMonth(12).toYear(2026).toMonths(List.of()).build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.copy(KioskFinancialsConfigCopyRequest.builder()
                .fromYear(2025).fromMonth(12).toYear(2026).toMonths(List.of(13)).build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.copy(KioskFinancialsConfigCopyRequest.builder()
                .fromYear(2025).fromMonth(12).toYear(2026).toMonths(List.of(1)).include(List.of("FOO")).build()))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------ config GET

    @Test
    void getConfigReturnsEmptyMonthsAsIncompleteAndCountsZeroAsValue() throws Exception {
        lenient().when(configRepository.findByPeriodYear(2026)).thenReturn(List.of(
                KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2026).periodMonth(1)
                        .salesGoal(new BigDecimal("130000")).productCostPct(new BigDecimal("0.18"))
                        .salesCommissionPct(new BigDecimal("0.04")).cardCommissionPct(new BigDecimal("0.025"))
                        .taxPct(new BigDecimal("0.025")).source("EXCEL").build()));
        lenient().when(fixedCostRepository.findByPeriodYear(2026)).thenReturn(List.of(
                cost(1, 2026, 1, "ALQUILER", "14674.89"), cost(1, 2026, 1, "LUZ", "0")));

        KioskFinancialsConfigResponse r = service.getConfig(2026, null, null);

        assertThat(r.getCategories()).extracting(KioskFinancialsConfigResponse.Category::getCode)
                .containsExactly("ALQUILER", "LUZ");
        List<KioskFinancialsConfigResponse.Month> months = r.getSites().get(0).getMonths();
        assertThat(months).hasSize(12);
        assertThat(months.get(0).getComplete()).isTrue();
        assertThat(months.get(0).getSource()).isEqualTo("EXCEL");
        assertThat(months.get(0).getCosts()).containsEntry("LUZ", new BigDecimal("0"));
        KioskFinancialsConfigResponse.Month feb = months.get(1);
        assertThat(feb.getComplete()).isFalse();
        assertThat(feb.getGoal()).isNull();
        assertThat(feb.getSource()).isNull();
        assertThat(feb.getCosts()).isEmpty();

        assertThat(service.getConfig(2026, 1L, 1).getSites().get(0).getMonths()).hasSize(1);
    }

    // ------------------------------------------------------------------ sitios

    @Test
    void updateSiteRejectsAliasOwnedByAnotherSite() {
        KioskSiteAliasEntity taken = KioskSiteAliasEntity.builder().id(9L).aliasNormalized("PERI").siteId(2L).build();
        lenient().when(aliasRepository.findByAliasNormalized("PERI")).thenReturn(Optional.of(taken));
        lenient().when(siteRepository.findById(2L)).thenReturn(Optional.of(
                KioskSiteEntity.builder().id(2L).name("PERI").build()));

        assertThatThrownBy(() -> service.updateSite(1L, KioskFinancialsSiteUpdateRequest.builder()
                .aliases(List.of("Perí ")).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PERI");
        verify(aliasRepository, never()).save(any());
    }

    @Test
    void updateSiteNormalizesAndReplacesAliasesAndHandlesGoLiveOverride() throws Exception {
        site1.setPosGoLiveOverride(LocalDate.parse("2026-07-01"));
        KioskSiteAliasEntity old = KioskSiteAliasEntity.builder().id(1L).aliasNormalized("VIEJO").siteId(1L).build();
        KioskSiteAliasEntity keep = KioskSiteAliasEntity.builder().id(2L).aliasNormalized("MIRAFLORES").siteId(1L).build();
        lenient().when(aliasRepository.findBySiteId(1L)).thenReturn(List.of(old, keep));
        lenient().when(aliasRepository.findByAliasNormalized(anyString())).thenReturn(Optional.empty());
        lenient().when(resolver.detectedGoLive(anyCollection())).thenReturn(Map.of(1L, LocalDate.parse("2026-07-21")));

        KioskFinancialsSiteResponse r = service.updateSite(1L, KioskFinancialsSiteUpdateRequest.builder()
                .aliases(List.of("MIRAFLORES ", "santalù", "SANTAL� ")).clearGoLiveOverride(true).build());

        assertThat(r.getAliases()).containsExactly("MIRAFLORES", "SANTAL", "SANTALU");
        assertThat(r.getPosGoLiveOverride()).isNull();
        assertThat(r.getPosGoLiveDetected()).isEqualTo(LocalDate.parse("2026-07-21"));
        assertThat(r.getGoLiveEffective()).isEqualTo(LocalDate.parse("2026-07-21"));
        verify(aliasRepository).deleteAll(List.of(old));
        verify(aliasRepository, times(2)).save(any(KioskSiteAliasEntity.class));
    }

    @Test
    void createSiteBuildsHistoricalSiteAndRejectsDuplicates() throws Exception {
        lenient().when(siteRepository.findByNameIgnoreCase("NUEVO KIOSCO")).thenReturn(Optional.empty());
        lenient().when(siteRepository.findByNameIgnoreCase("MIRAFLORES II")).thenReturn(Optional.of(site1));
        lenient().when(siteRepository.findMaxSortOrder()).thenReturn(1005);
        lenient().when(siteRepository.save(any(KioskSiteEntity.class))).thenAnswer(i -> {
            KioskSiteEntity s = i.getArgument(0);
            s.setId(50L);
            return s;
        });
        lenient().when(aliasRepository.findByAliasNormalized("NUEVO KIOSCO")).thenReturn(Optional.empty());

        KioskFinancialsSiteResponse r = service.createSite(KioskFinancialsSiteCreateRequest.builder()
                .name("  Nuevo   Kiosco ").build());

        assertThat(r.getId()).isEqualTo(50L);
        assertThat(r.getName()).isEqualTo("Nuevo Kiosco");
        assertThat(r.getLocationId()).isNull();
        assertThat(r.getStatus()).isEqualTo("ACTIVE");
        assertThat(r.getAliases()).containsExactly("NUEVO KIOSCO");

        assertThatThrownBy(() -> service.createSite(KioskFinancialsSiteCreateRequest.builder().name("MIRAFLORES II").build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createSite(KioskFinancialsSiteCreateRequest.builder().name(" ").build()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aliasNormalizerFollowsContract() {
        assertThat(KioskSiteAliasNormalizer.normalize("MIRAFLORES ")).isEqualTo("MIRAFLORES");
        assertThat(KioskSiteAliasNormalizer.normalize("SANTALÙ")).isEqualTo("SANTALU");
        assertThat(KioskSiteAliasNormalizer.normalize("SANTAL�")).isEqualTo("SANTAL");
        assertThat(KioskSiteAliasNormalizer.normalize("  Zona   4 ")).isEqualTo("ZONA 4");
        assertThat(KioskSiteAliasNormalizer.normalize(null)).isEmpty();
    }
}
