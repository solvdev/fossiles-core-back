package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompareResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompletenessResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsDailyMatrixResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsPnlResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCostCategoryRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KioskFinancialsReportServiceTest {

    @Mock
    private KioskFinancialsAccessGuard guard;
    @Mock
    private KioskSiteRepository siteRepository;
    @Mock
    private KioskCostCategoryRepository categoryRepository;
    @Mock
    private KioskFixedCostRepository fixedCostRepository;
    @Mock
    private KioskPeriodConfigRepository configRepository;
    @Mock
    private KioskSalesSourceResolver resolver;
    @InjectMocks
    private KioskFinancialsReportService service;

    private KioskSiteEntity site1;
    private KioskSiteEntity site2;

    @BeforeEach
    void setUp() {
        site1 = KioskSiteEntity.builder().id(1L).name("MIRAFLORES II").locationId(15L)
                .status("ACTIVE").sortOrder(1).build();
        site2 = KioskSiteEntity.builder().id(2L).name("MAJADAS 11").locationId(null)
                .status("CLOSED").sortOrder(2).build();
        lenient().when(siteRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(site1, site2));
        lenient().when(categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                KioskCostCategoryEntity.builder().code("ALQUILER").name("Alquiler").sortOrder(1).active(true).build(),
                KioskCostCategoryEntity.builder().code("LUZ").name("Luz").sortOrder(2).active(true).build()));
        lenient().when(resolver.goLiveEffective(any())).thenReturn(Map.of());
    }

    // ------------------------------------------------------------------ helpers

    private static SiteSales sales(long siteId, LocalDate goLive, boolean pos, boolean hist, Object... dateAmountPairs) {
        TreeMap<LocalDate, BigDecimal> daily = new TreeMap<>();
        for (int i = 0; i < dateAmountPairs.length; i += 2) {
            daily.put(LocalDate.parse((String) dateAmountPairs[i]), new BigDecimal((String) dateAmountPairs[i + 1]));
        }
        return new SiteSales(siteId, goLive, daily, pos, hist);
    }

    private static KioskFixedCostEntity cost(long siteId, int year, int month, String code, String amount) {
        return KioskFixedCostEntity.builder().siteId(siteId).periodYear(year).periodMonth(month)
                .categoryCode(code).amount(new BigDecimal(amount)).build();
    }

    private static KioskPeriodConfigEntity config(long siteId, int year, int month, String goal, String pc, String sc,
                                                  String tc, String tx) {
        return KioskPeriodConfigEntity.builder().siteId(siteId).periodYear(year).periodMonth(month)
                .salesGoal(goal == null ? null : new BigDecimal(goal))
                .productCostPct(pc == null ? null : new BigDecimal(pc))
                .salesCommissionPct(sc == null ? null : new BigDecimal(sc))
                .cardCommissionPct(tc == null ? null : new BigDecimal(tc))
                .taxPct(tx == null ? null : new BigDecimal(tx)).source("MANUAL").build();
    }

    // ------------------------------------------------------------------ compare

    @Test
    void compareSamePeriodAlignsLeapDayAndProratesCosts() throws Exception {
        LocalDate goLive = LocalDate.parse("2028-02-10");
        lenient().when(resolver.goLiveEffective(any())).thenReturn(Map.of(1L, goLive));
        // 2028 es bisiesto (29 dias), 2027 no (28 dias)
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2028, 1, 1)), eq(LocalDate.of(2028, 2, 29)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, goLive, true, true,
                                "2028-01-05", "9999.00",  // antes del go-live: fuera del periodo
                                "2028-02-10", "100.00",
                                "2028-02-29", "50.00"),
                        2L, sales(2, null, false, false)));
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2027, 1, 1)), eq(LocalDate.of(2027, 2, 28)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, goLive, false, true,
                                "2027-02-09", "1000.00", // antes del periodo base: fuera
                                "2027-02-10", "80.00",
                                "2027-02-28", "40.00"),
                        2L, sales(2, null, false, false)));
        lenient().when(fixedCostRepository.findByPeriodYear(2028)).thenReturn(List.of(cost(1, 2028, 2, "ALQUILER", "2900.00")));
        lenient().when(fixedCostRepository.findByPeriodYear(2027)).thenReturn(List.of(cost(1, 2027, 2, "ALQUILER", "2800.00")));

        KioskFinancialsCompareResponse r = service.compare(2028, 2027, 1, 2, "SAME_PERIOD", null, LocalDate.of(2028, 9, 30));

        assertThat(r.getSites()).hasSize(1); // el sitio sin ventas en ninguno de los anios no aparece
        KioskFinancialsCompareResponse.SiteCompare s = r.getSites().get(0);
        assertThat(s.getPeriodFrom()).isEqualTo(LocalDate.of(2028, 2, 10));
        assertThat(s.getPeriodTo()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(s.getBasePeriodFrom()).isEqualTo(LocalDate.of(2027, 2, 10));
        assertThat(s.getBasePeriodTo()).isEqualTo(LocalDate.of(2027, 2, 28)); // 29-feb -> 28-feb
        assertThat(s.getSales()).isEqualByComparingTo("150.00");
        assertThat(s.getBaseSales()).isEqualByComparingTo("120.00");
        assertThat(s.getDelta()).isEqualByComparingTo("30.00");
        assertThat(s.getDeltaPct()).isEqualByComparingTo("0.2500");
        // Alquiler prorrateado por dias: 2900 * 20/29 = 2000 ; 2800 * 19/28 = 1900
        assertThat(s.getDifference()).isEqualByComparingTo("-1850.00");
        assertThat(s.getBaseDifference()).isEqualByComparingTo("-1780.00");
        assertThat(r.getTotals().getSales()).isEqualByComparingTo("150.00");
        assertThat(r.getMonthly()).hasSize(2);
        assertThat(r.getMonthly().get(1).getMonth()).isEqualTo(2);
        assertThat(r.getMonthly().get(1).getTotalCost()).isEqualByComparingTo("2000.00");
        assertThat(r.getMonthly().get(1).getBaseTotalCost()).isEqualByComparingTo("1900.00");
        assertThat(r.getMonthly().get(0).getSales()).isEqualByComparingTo("0");
    }

    @Test
    void compareSamePeriodIsCappedByTodayAndGoLive() throws Exception {
        LocalDate goLive = LocalDate.parse("2026-07-21");
        lenient().when(resolver.goLiveEffective(any())).thenReturn(Map.of(1L, goLive));
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)), any()))
                .thenReturn(Map.of(1L, sales(1, goLive, true, false, "2026-07-21", "100.00", "2026-09-30", "50.00")));
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2025, 12, 31)), any()))
                .thenReturn(Map.of(1L, sales(1, goLive, false, true,
                        "2025-07-20", "500.00", "2025-07-21", "60.00", "2025-09-30", "10.00", "2025-10-01", "700.00")));

        KioskFinancialsCompareResponse r = service.compare(2026, 2025, 1, 12, "SAME_PERIOD", "1", LocalDate.of(2026, 9, 30));

        KioskFinancialsCompareResponse.SiteCompare s = r.getSites().get(0);
        assertThat(s.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(s.getPeriodTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(s.getBasePeriodFrom()).isEqualTo(LocalDate.of(2025, 7, 21));
        assertThat(s.getBasePeriodTo()).isEqualTo(LocalDate.of(2025, 9, 30));
        assertThat(s.getSales()).isEqualByComparingTo("150.00");
        assertThat(s.getBaseSales()).isEqualByComparingTo("70.00");
        assertThat(r.getAsOf()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(r.getMode()).isEqualTo("SAME_PERIOD");
    }

    @Test
    void compareFullMonthUsesWholeMonths() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2026, 3, 1)), eq(LocalDate.of(2026, 4, 30)), any()))
                .thenReturn(Map.of(1L, sales(1, null, false, true, "2026-03-10", "100.00")));
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 3, 1)), eq(LocalDate.of(2025, 4, 30)), any()))
                .thenReturn(Map.of(1L, sales(1, null, false, true, "2025-04-30", "40.00")));

        KioskFinancialsCompareResponse r = service.compare(2026, 2025, 3, 4, "FULL_MONTH", "1", LocalDate.of(2026, 3, 15));

        KioskFinancialsCompareResponse.SiteCompare s = r.getSites().get(0);
        assertThat(s.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(s.getPeriodTo()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(s.getBasePeriodTo()).isEqualTo(LocalDate.of(2025, 4, 30));
        assertThat(s.getSales()).isEqualByComparingTo("100.00");
        assertThat(s.getBaseSales()).isEqualByComparingTo("40.00");
    }

    @Test
    void compareDeltaPctIsNullWhenBaseIsZero() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 1, 31)), any()))
                .thenReturn(Map.of(1L, sales(1, null, false, true, "2026-01-10", "100.00")));
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2025, 1, 31)), any()))
                .thenReturn(Map.of(1L, sales(1, null, false, true)));

        KioskFinancialsCompareResponse r = service.compare(2026, 2025, 1, 1, "FULL_MONTH", "1", LocalDate.of(2026, 6, 1));

        assertThat(r.getSites().get(0).getDeltaPct()).isNull();
        assertThat(r.getTotals().getDeltaPct()).isNull();
    }

    @Test
    void alignToBaseYearMapsLeapDay() {
        assertThat(KioskFinancialsReportService.alignToBaseYear(LocalDate.of(2028, 2, 29), 2027))
                .isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(KioskFinancialsReportService.alignToBaseYear(LocalDate.of(2027, 2, 28), 2028))
                .isEqualTo(LocalDate.of(2028, 2, 28));
        assertThat(KioskFinancialsReportService.alignToBaseYear(LocalDate.of(2026, 7, 21), 2025))
                .isEqualTo(LocalDate.of(2025, 7, 21));
    }

    @Test
    void compareRejectsInvalidMode() {
        assertThatThrownBy(() -> service.compare(2026, 2025, 1, 12, "WHATEVER", null, LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.compare(2026, 2025, 5, 2, "SAME_PERIOD", null, LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------ completeness

    @Test
    void completenessRequiresAllCategoriesAndZeroCountsAsValue() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2025, 12, 31)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, null, false, true, "2025-01-10", "0.00", "2025-03-05", "10.00"),
                        2L, sales(2, null, false, true)));
        lenient().when(fixedCostRepository.findByPeriodYear(2025)).thenReturn(List.of(
                cost(1, 2025, 1, "ALQUILER", "100.00"), cost(1, 2025, 1, "LUZ", "0.00"),
                cost(1, 2025, 2, "ALQUILER", "100.00")));
        lenient().when(configRepository.findByPeriodYear(2025)).thenReturn(List.of(
                config(1, 2025, 1, "5000", null, null, null, null),
                config(1, 2025, 2, null, "0.18", "0.04", "0.02", "0.025")));

        KioskFinancialsCompletenessResponse r = service.getCompleteness(2025);

        assertThat(r.getSites()).hasSize(1); // site2 esta cerrado y sin ningun dato
        KioskFinancialsCompletenessResponse.Site s = r.getSites().get(0);
        assertThat(s.getSiteId()).isEqualTo(1L);
        assertThat(s.getMonths()).hasSize(12);
        KioskFinancialsCompletenessResponse.Month jan = s.getMonths().get(0);
        assertThat(jan.getHasSales()).isTrue();  // fila con 0 cuenta como dato
        assertThat(jan.getHasCosts()).isTrue();  // 0 cuenta como valor
        assertThat(jan.getHasGoal()).isTrue();
        KioskFinancialsCompletenessResponse.Month feb = s.getMonths().get(1);
        assertThat(feb.getHasSales()).isFalse();
        assertThat(feb.getHasCosts()).isFalse(); // falta LUZ
        assertThat(feb.getHasGoal()).isFalse();  // config sin meta
        assertThat(s.getMonths().get(2).getHasSales()).isTrue();
        assertThat(s.getMonths().get(3).getHasCosts()).isFalse();
    }

    @Test
    void completenessKeepsClosedSiteThatHasData() throws Exception {
        lenient().when(resolver.resolve(any(), any(), any(), any())).thenReturn(Map.of(
                1L, sales(1, null, false, true), 2L, sales(2, null, false, true, "2025-02-02", "5.00")));

        KioskFinancialsCompletenessResponse r = service.getCompleteness(2025);

        assertThat(r.getSites()).extracting(KioskFinancialsCompletenessResponse.Site::getSiteId)
                .containsExactly(1L, 2L);
    }

    // ------------------------------------------------------------------ P&L

    @Test
    void pnlMonthAppliesFormulasAndParticipation() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2025, 1, 31)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, null, false, true, "2025-01-02", "60000.00", "2025-01-03", "14038.80"),
                        2L, sales(2, null, false, true, "2025-01-02", "25961.20")));
        lenient().when(configRepository.findByPeriodYear(2025)).thenReturn(List.of(
                config(1, 2025, 1, "130000", "0.18", "0.04", "0.025", "0.025")));
        lenient().when(fixedCostRepository.findByPeriodYear(2025)).thenReturn(List.of(
                cost(1, 2025, 1, "ALQUILER", "14674.89"), cost(1, 2025, 1, "LUZ", "0.00")));

        KioskFinancialsPnlResponse r = service.getPnl(2025, 1, null);

        assertThat(r.getSites()).hasSize(2);
        KioskFinancialsPnlResponse.SitePnl s1 = r.getSites().get(0);
        assertThat(s1.getSales()).isEqualByComparingTo("74038.80");
        assertThat(s1.getVariable().getProductCost()).isEqualByComparingTo("13326.98");
        assertThat(s1.getVariable().getSalesCommission()).isEqualByComparingTo("2644.24");
        assertThat(s1.getFixed().getTotal()).isEqualByComparingTo("14674.89");
        assertThat(s1.getFixed().getByCategory()).containsKeys("ALQUILER", "LUZ");
        assertThat(s1.getTotalCost()).isEqualByComparingTo("34348.06");
        assertThat(s1.getDifference()).isEqualByComparingTo("39690.74");
        assertThat(s1.getBreakEven()).isEqualByComparingTo("20102.59"); // 14674.89 / 0.73
        assertThat(s1.getBreakEvenDaily()).isEqualByComparingTo("648.47"); // dias reales: 31
        assertThat(s1.getGoalPct()).isEqualByComparingTo("0.5695");
        assertThat(s1.getParticipationPct()).isEqualByComparingTo("0.7404");
        assertThat(s1.getDaysWithSales()).isEqualTo(2);
        assertThat(s1.getSource()).isEqualTo("HIST");
        assertThat(s1.getComplete()).isTrue();
        assertThat(s1.getByMonth()).isNull();

        KioskFinancialsPnlResponse.SitePnl s2 = r.getSites().get(1);
        assertThat(s2.getComplete()).isFalse(); // sin configuracion
        assertThat(s2.getTotalCost()).isEqualByComparingTo("0.00");
        assertThat(r.getTotals().getSales()).isEqualByComparingTo("100000.00");
        assertThat(r.getTotals().getDaysWithSales()).isEqualTo(2);
    }

    @Test
    void pnlYearSumsOnlyMonthsWithSalesAndReportsByMonth() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 1, 1)), eq(LocalDate.of(2025, 12, 31)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, null, false, true, "2025-01-10", "1000.00", "2025-03-10", "2000.00"),
                        2L, sales(2, null, false, true)));
        lenient().when(configRepository.findByPeriodYear(2025)).thenReturn(List.of(
                config(1, 2025, 1, "1500", "0", "0", "0", "0"),
                config(1, 2025, 2, "1500", "0", "0", "0", "0"),
                config(1, 2025, 3, "1500", "0", "0", "0", "0")));
        lenient().when(fixedCostRepository.findByPeriodYear(2025)).thenReturn(List.of(
                cost(1, 2025, 1, "ALQUILER", "100.00"), cost(1, 2025, 2, "ALQUILER", "100.00"),
                cost(1, 2025, 3, "ALQUILER", "300.00")));

        KioskFinancialsPnlResponse r = service.getPnl(2025, null, "1");

        assertThat(r.getMonth()).isNull();
        KioskFinancialsPnlResponse.SitePnl s = r.getSites().get(0);
        assertThat(s.getSales()).isEqualByComparingTo("3000.00");
        assertThat(s.getFixed().getTotal()).isEqualByComparingTo("400.00"); // febrero (sin ventas) no cuenta
        assertThat(s.getGoal()).isEqualByComparingTo("3000.00");
        assertThat(s.getByMonth()).extracting(KioskFinancialsPnlResponse.MonthLine::getMonth).containsExactly(1, 3);
        assertThat(s.getByMonth().get(0).getDifference()).isEqualByComparingTo("900.00");
        assertThat(s.getByMonth().get(1).getDifference()).isEqualByComparingTo("1700.00");
        assertThat(s.getMargin()).isEqualByComparingTo("0.8667");
        assertThat(s.getBreakEven()).isEqualByComparingTo("400.00");
        assertThat(s.getBreakEvenDaily()).isEqualByComparingTo("6.45"); // 400 / (31 + 31) dias de los meses con ventas
    }

    // ------------------------------------------------------------------ daily matrix

    @Test
    void dailyMatrixDistinguishesMissingFromZeroAndAccumulates() throws Exception {
        lenient().when(resolver.resolve(any(), eq(LocalDate.of(2025, 2, 1)), eq(LocalDate.of(2025, 2, 28)), any()))
                .thenReturn(Map.of(
                        1L, sales(1, null, false, true, "2025-02-02", "100.00", "2025-02-03", "0.00"),
                        2L, sales(2, null, false, true, "2025-02-02", "50.50")));

        KioskFinancialsDailyMatrixResponse r = service.getDailyMatrix(2025, 2, null);

        assertThat(r.getSites()).hasSize(2);
        assertThat(r.getDays()).hasSize(28);
        KioskFinancialsDailyMatrixResponse.Day d1 = r.getDays().get(0);
        assertThat(d1.getValues().get(1L)).isNull();
        assertThat(d1.getTotal()).isEqualByComparingTo("0");
        KioskFinancialsDailyMatrixResponse.Day d2 = r.getDays().get(1);
        assertThat(d2.getValues().get(1L)).isEqualByComparingTo("100.00");
        assertThat(d2.getValues().get(2L)).isEqualByComparingTo("50.50");
        assertThat(d2.getTotal()).isEqualByComparingTo("150.50");
        KioskFinancialsDailyMatrixResponse.Day d3 = r.getDays().get(2);
        assertThat(d3.getValues().get(1L)).isNotNull();
        assertThat(d3.getValues().get(1L)).isEqualByComparingTo("0");
        assertThat(d3.getValues().get(2L)).isNull();
        assertThat(d3.getCumulative()).isEqualByComparingTo("150.50");
        assertThat(r.getSiteTotals().get(1L)).isEqualByComparingTo("100.00");
        assertThat(r.getGrandTotal()).isEqualByComparingTo("150.50");
    }

    @Test
    void rejectsInvalidSiteIdsAndMonths() {
        assertThatThrownBy(() -> service.getPnl(2025, 13, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPnl(2025, 1, "abc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPnl(2025, 1, "99")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getDailyMatrix(2025, null, null)).isInstanceOf(BusinessException.class);
    }
}
