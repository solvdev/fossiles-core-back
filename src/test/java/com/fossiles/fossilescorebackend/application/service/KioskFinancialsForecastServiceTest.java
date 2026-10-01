package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsForecastResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.util.KioskEffectiveGoals;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPeriodConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

/**
 * Escenario de números redondos (hoy = 16-sep-2026): un kiosco maduro vende 100/día en 2025 y 110/día en 2026
 * (crecimiento 10 %), y uno nuevo arrancó el 5-sep con 200/día.
 */
@ExtendWith(MockitoExtension.class)
class KioskFinancialsForecastServiceTest {

    private static final String AS_OF = "2026-09-16";

    @Mock
    private KioskFinancialsAccessGuard guard;
    @Mock
    private KioskSiteRepository siteRepository;
    @Mock
    private KioskSalesSourceResolver resolver;
    @Mock
    private KioskPeriodConfigRepository configRepository;
    @Mock
    private KioskFixedCostRepository fixedCostRepository;
    @Mock
    private KioskGoalModuleReader goalReader;
    @Mock
    private KioskFinancialsSettingsService settingsService;

    @InjectMocks
    private KioskFinancialsForecastService service;

    private KioskSiteEntity mature;
    private KioskSiteEntity fresh;
    private KioskSiteEntity pueblito;

    @BeforeEach
    void setUp() {
        mature = KioskSiteEntity.builder().id(1L).name("MADURO").locationId(15L).status("ACTIVE").sortOrder(1).build();
        fresh = KioskSiteEntity.builder().id(2L).name("NUEVO").locationId(16L).status("ACTIVE").sortOrder(2).build();
        pueblito = KioskSiteEntity.builder().id(3L).name("ENTRECUEROS PUEBLITO").locationId(17L).status("ACTIVE")
                .sortOrder(3).excludeFromReports(true).build();
        lenient().when(siteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(mature, fresh));
        lenient().when(settingsService.breakEvenMode()).thenReturn("RATES");

        TreeMap<LocalDate, BigDecimal> maturDaily = new TreeMap<>();
        for (LocalDate d = LocalDate.of(2025, 1, 1); d.isBefore(LocalDate.of(2026, 9, 16)); d = d.plusDays(1)) {
            maturDaily.put(d, new BigDecimal(d.getYear() == 2025 ? "100" : "110"));
        }
        TreeMap<LocalDate, BigDecimal> freshDaily = new TreeMap<>();
        for (LocalDate d = LocalDate.of(2026, 9, 5); d.isBefore(LocalDate.of(2026, 9, 16)); d = d.plusDays(1)) {
            freshDaily.put(d, new BigDecimal("200"));
        }
        lenient().when(resolver.resolve(anyCollection(), any(LocalDate.class), any(LocalDate.class))).thenReturn(Map.of(
                1L, new SiteSales(1L, LocalDate.of(2025, 1, 1), maturDaily, true, false),
                2L, new SiteSales(2L, LocalDate.of(2026, 9, 5), freshDaily, true, false)));

        lenient().when(configRepository.findByPeriodYear(anyInt())).thenAnswer(inv -> inv.<Integer>getArgument(0) == 2026
                ? List.of(KioskPeriodConfigEntity.builder().siteId(1L).periodYear(2026).periodMonth(9)
                .productCostPct(new BigDecimal("0.18")).salesCommissionPct(BigDecimal.ZERO)
                .cardCommissionPct(new BigDecimal("0.0287")).taxPct(new BigDecimal("0.025")).source("MANUAL").build())
                : List.of());
        lenient().when(fixedCostRepository.findByPeriodYear(anyInt())).thenAnswer(inv -> inv.<Integer>getArgument(0) == 2026
                ? List.of(KioskFixedCostEntity.builder().siteId(1L).periodYear(2026).periodMonth(9)
                .categoryCode("ALQUILER").amount(new BigDecimal("1000")).build())
                : List.of());
        lenient().when(goalReader.forYear(anyInt(), any())).thenReturn(new KioskEffectiveGoals(
                Map.of(1L, 15L), Map.of(15L, Map.of(9, new BigDecimal("3000")))));
    }

    private static KioskFinancialsForecastResponse.Site byName(KioskFinancialsForecastResponse.MonthEnd r, String name) {
        return r.getSites().stream().filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ cierre del mes

    @Test
    void monthEndProjectsTheCloseAgainstTheGoalAndBuildsTheProjectedPnl() throws Exception {
        KioskFinancialsForecastResponse.MonthEnd r = service.monthEnd(null, AS_OF);

        assertThat(r.getYear()).isEqualTo(2026);
        assertThat(r.getMonth()).isEqualTo(9);
        assertThat(r.getDaysElapsed()).isEqualTo(15);
        assertThat(r.getDaysRemaining()).isEqualTo(15);

        KioskFinancialsForecastResponse.Site a = byName(r, "MADURO");
        assertThat(a.getMethod()).isEqualTo("WEEKDAY");
        assertThat(a.getMtd()).isEqualByComparingTo("1650.00");          // 15 días × 110
        assertThat(a.getProjected()).isEqualByComparingTo("3300.00");    // 30 días × 110
        assertThat(a.getGoal()).isEqualByComparingTo("3000.00");
        assertThat(a.getGoalPctProjected()).isEqualByComparingTo("1.1000");
        assertThat(a.getGoalPctToDate()).isEqualByComparingTo("0.5500");
        // P&L proyectado con los costos y tasas de septiembre: 3300 × 0.2337 + (3300 ÷ 1.12) × 4 % (comisión fija,
        // aplica: 3300 es 110 % de la meta de 3000) + bono por meta Q800 (>= 100 %, desde sept-2026) + 1000
        // = 771.21 + 117.86 + 800 + 1000
        assertThat(a.getCostsFrom()).isEqualTo("2026-09");
        assertThat(a.getTotalCost()).isEqualByComparingTo("2689.07");
        assertThat(a.getDifference()).isEqualByComparingTo("610.93");
        assertThat(a.getBelowBreakEven()).isFalse();

        KioskFinancialsForecastResponse.Site b = byName(r, "NUEVO");
        assertThat(b.getMethod()).isEqualTo("RUN_RATE");
        assertThat(b.getProjected()).isEqualByComparingTo("5200.00");    // 11 días reales (5..15) + 15 días por proyectar × 200
        assertThat(b.getGoal()).isNull();
        assertThat(b.getTotalCost()).isNull();                            // sin costos configurados: no hay P&L

        assertThat(r.getTotals().getSitesProjected()).isEqualTo(2);
        assertThat(r.getTotals().getProjected()).isEqualByComparingTo("8500.00");
    }

    @Test
    void sitesCanBeFilteredAndExternalOnesNeverAppear() throws Exception {
        KioskFinancialsForecastResponse.MonthEnd r = service.monthEnd("1", AS_OF);
        assertThat(r.getSites()).extracting(KioskFinancialsForecastResponse.Site::getName).containsExactly("MADURO");

        // Pueblito está fuera de los reportes: pedirlo por id da error
        assertThatThrownBy(() -> service.monthEnd(String.valueOf(pueblito.getId()), AS_OF))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.monthEnd(null, "ayer")).isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------ año siguiente

    @Test
    void nextYearAppliesTheSiteGrowthAndBuildsTheProjectedPnl() throws Exception {
        KioskFinancialsForecastResponse.NextYear r = service.nextYear(null, null, null, AS_OF);

        assertThat(r.getBaseYear()).isEqualTo(2026);
        assertThat(r.getTargetYear()).isEqualTo(2027);
        assertThat(r.getGrowthMode()).isEqualTo("SITE_OR_COMPANY");
        assertThat(r.getCompanyGrowthFactor()).isEqualByComparingTo("1.1000");
        // el kiosco nuevo no tiene historia suficiente: no se proyecta el año
        assertThat(r.getSites()).extracting(KioskFinancialsForecastResponse.SiteYear::getName).containsExactly("MADURO");
        assertThat(r.getSkippedSites()).containsExactly("NUEVO");

        KioskFinancialsForecastResponse.SiteYear a = r.getSites().get(0);
        assertThat(a.getGrowthSource()).isEqualTo("SITE");
        assertThat(a.getGrowthFactor()).isEqualByComparingTo("1.1000");
        assertThat(a.getGrowthCapped()).isFalse();
        // enero 2027 = enero 2026 (110 × 31) × 1.1 = 3751
        assertThat(a.getMonths().get(0).getBaseSales()).isEqualByComparingTo("3410.00");
        assertThat(a.getMonths().get(0).getSales()).isEqualByComparingTo("3751.00");
        // septiembre: base = cierre proyectado (3300) × 1.1
        assertThat(a.getMonths().get(8).getSales()).isEqualByComparingTo("3630.00");
        // octubre: aún no ocurre => octubre 2025 (100 × 31) × 1.1 × 1.1 = 3751
        assertThat(a.getMonths().get(9).getBaseSales()).isEqualByComparingTo("3410.00");
        assertThat(a.getMonths().get(9).getSales()).isEqualByComparingTo("3751.00");
        assertThat(a.getEstimatedMonths()).isZero();
        // total anual = 121 × 365
        assertThat(a.getSales()).isEqualByComparingTo("44165.00");
        // P&L: enero = 3751 × (1 - 0.2337) - (3751 ÷ 1.12) × 4 % - 1000 (sin metas del año siguiente la comisión aplica)
        assertThat(a.getCostsFrom()).isEqualTo("2026-09");
        assertThat(a.getMonths().get(0).getDifference()).isEqualByComparingTo("1740.43");
        assertThat(a.getMonths().get(0).getBreakEven()).isEqualByComparingTo("1376.84"); // 1000 / 0.7263
        assertThat(r.getTotals().getSales()).isEqualByComparingTo("44165.00");
        assertThat(r.getSeasonalIndex()).hasSize(12);
    }

    @Test
    void growthOverrideReplacesTheComputedFactorAndIsValidated() throws Exception {
        KioskFinancialsForecastResponse.NextYear r = service.nextYear(null, 2027, new BigDecimal("5"), AS_OF);

        assertThat(r.getGrowthMode()).isEqualTo("OVERRIDE");
        assertThat(r.getSites().get(0).getGrowthSource()).isEqualTo("OVERRIDE");
        assertThat(r.getSites().get(0).getGrowthFactor()).isEqualByComparingTo("1.0500");
        // enero 2027 = 3410 × 1.05
        assertThat(r.getSites().get(0).getMonths().get(0).getSales()).isEqualByComparingTo("3580.50");

        assertThatThrownBy(() -> service.nextYear(null, 2027, new BigDecimal("150"), AS_OF))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.nextYear(null, 2030, null, AS_OF)).isInstanceOf(BusinessException.class);
    }
}
