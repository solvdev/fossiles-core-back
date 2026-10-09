package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosSalesAggregateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KioskSalesSourceResolverTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Mock
    private KioskPosSalesAggregateRepository posRepository;
    @Mock
    private KioskDailySalesHistRepository histRepository;
    @InjectMocks
    private KioskSalesSourceResolver resolver;

    private KioskSiteEntity posSite;
    private KioskSiteEntity histSite;

    @BeforeEach
    void setUp() {
        posSite = KioskSiteEntity.builder().id(1L).name("MIRAFLORES II").locationId(15L).build();
        histSite = KioskSiteEntity.builder().id(2L).name("MAJADAS 11").locationId(null).build();
    }

    private static List<Object[]> rows(Object[]... rows) {
        return Arrays.asList(rows);
    }

    private static KioskDailySalesHistEntity hist(long siteId, String date, String amount) {
        return KioskDailySalesHistEntity.builder()
                .siteId(siteId).saleDate(LocalDate.parse(date)).amount(new BigDecimal(amount)).build();
    }

    private void stubPos(String detectedGoLive, Object[]... salesRows) {
        lenient().when(posRepository.findFirstRealSaleDateByLocation(anyCollection()))
                .thenReturn(detectedGoLive == null ? List.of() : rows(new Object[]{15L, LocalDate.parse(detectedGoLive)}));
        lenient().when(posRepository.sumRealSalesByLocationAndDate(anyCollection(), any(), any()))
                .thenReturn(rows(salesRows));
    }

    @Test
    void usesHistBeforeGoLiveAndPosFromGoLive() {
        stubPos("2026-07-21",
                new Object[]{15L, LocalDate.parse("2026-07-21"), new BigDecimal("200.00")},
                new Object[]{15L, LocalDate.parse("2026-07-22"), new BigDecimal("300.00")},
                // venta POS anterior al go-live: no puede pasar por definicion, pero se ignora igual
                new Object[]{15L, LocalDate.parse("2026-07-20"), new BigDecimal("50.00")});
        lenient().when(histRepository.findBySitesAndRange(anyCollection(), any(), any())).thenReturn(List.of(
                hist(1, "2026-07-19", "100.00"),
                hist(1, "2026-07-21", "999.00"),   // solapa con POS: se ignora
                hist(1, "2026-07-25", "888.00")));  // idem

        Map<Long, SiteSales> result = resolver.resolve(List.of(posSite), FROM, TO);
        SiteSales s = result.get(1L);

        assertThat(s.goLive()).isEqualTo(LocalDate.parse("2026-07-21"));
        assertThat(s.daily()).containsOnlyKeys(
                LocalDate.parse("2026-07-19"), LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-22"));
        assertThat(s.daily().get(LocalDate.parse("2026-07-19"))).isEqualByComparingTo("100.00");
        assertThat(s.daily().get(LocalDate.parse("2026-07-21"))).isEqualByComparingTo("200.00");
        assertThat(s.total()).isEqualByComparingTo("600.00");
        assertThat(s.source()).isEqualTo("MIXED");
    }

    @Test
    void overrideWinsOverDetectedGoLive() {
        posSite.setPosGoLiveOverride(LocalDate.parse("2026-08-01"));
        stubPos("2026-07-21",
                new Object[]{15L, LocalDate.parse("2026-07-25"), new BigDecimal("777.00")}, // antes del override
                new Object[]{15L, LocalDate.parse("2026-08-02"), new BigDecimal("300.00")});
        lenient().when(histRepository.findBySitesAndRange(anyCollection(), any(), any())).thenReturn(List.of(
                hist(1, "2026-07-25", "400.00"),   // < override: hist gana
                hist(1, "2026-08-02", "999.00"))); // >= override: se ignora

        SiteSales s = resolver.resolve(List.of(posSite), FROM, TO).get(1L);

        assertThat(s.goLive()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(s.daily().get(LocalDate.parse("2026-07-25"))).isEqualByComparingTo("400.00");
        assertThat(s.daily().get(LocalDate.parse("2026-08-02"))).isEqualByComparingTo("300.00");
        assertThat(s.daily()).hasSize(2);
        assertThat(s.source()).isEqualTo("MIXED");
    }

    @Test
    void siteWithoutPosSalesUsesHistOnly() {
        stubPos(null);
        lenient().when(histRepository.findBySitesAndRange(anyCollection(), any(), any())).thenReturn(List.of(
                hist(1, "2026-07-25", "400.00"), hist(1, "2026-08-25", "0.00")));

        SiteSales s = resolver.resolve(List.of(posSite), FROM, TO).get(1L);

        assertThat(s.goLive()).isNull();
        assertThat(s.source()).isEqualTo("HIST");
        assertThat(s.daily()).hasSize(2); // 0 es dato; no es "sin venta"
        assertThat(s.total()).isEqualByComparingTo("400.00");
    }

    @Test
    void historicalSiteWithoutLocationIgnoresGoLiveCutoff() {
        histSite.setPosGoLiveOverride(LocalDate.parse("2026-07-10"));
        lenient().when(histRepository.findBySitesAndRange(anyCollection(), any(), any())).thenReturn(List.of(
                hist(2, "2026-07-05", "10.00"), hist(2, "2026-07-15", "20.00")));

        SiteSales s = resolver.resolve(List.of(histSite), FROM, TO).get(2L);

        assertThat(s.total()).isEqualByComparingTo("30.00");
        assertThat(s.source()).isEqualTo("HIST");
    }

    @Test
    void posOnlySiteReportsPosSource() {
        stubPos("2026-07-21", new Object[]{15L, LocalDate.parse("2026-07-22"), new BigDecimal("300.00")});
        lenient().when(histRepository.findBySitesAndRange(anyCollection(), any(), any())).thenReturn(List.of());

        SiteSales s = resolver.resolve(List.of(posSite), FROM, TO).get(1L);

        assertThat(s.source()).isEqualTo("POS");
        assertThat(s.totalBetween(LocalDate.parse("2026-07-23"), null)).isEqualByComparingTo("0");
    }

    @Test
    void emptyRangeReturnsEmptySalesWithoutQueries() {
        Map<Long, SiteSales> result = resolver.resolve(List.of(posSite), TO, FROM, Map.of());

        assertThat(result.get(1L).hasData()).isFalse();
    }

    @Test
    void goLiveEffectiveIsOverrideThenDetected() {
        stubPos("2026-07-21");
        KioskSiteEntity other = KioskSiteEntity.builder().id(3L).name("X").locationId(16L)
                .posGoLiveOverride(LocalDate.parse("2026-01-01")).build();

        Map<Long, LocalDate> effective = resolver.goLiveEffective(List.of(posSite, histSite, other));

        assertThat(effective).containsEntry(1L, LocalDate.parse("2026-07-21"))
                .containsEntry(3L, LocalDate.parse("2026-01-01"))
                .doesNotContainKey(2L);
    }
}
