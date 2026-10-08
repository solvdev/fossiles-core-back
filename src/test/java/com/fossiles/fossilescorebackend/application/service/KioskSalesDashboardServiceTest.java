package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.DailyPoint;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.KioskOption;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.ProductRank;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SaleRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.TrendPoint;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosSalesAggregateRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.header;
import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.hist;
import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.item;
import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.site;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Canal KIOSKO del dashboard sobre la fuente de Finanzas kioscos. Se usa el {@link KioskSalesSourceResolver} real
 * con repositorios simulados (que reproducen el SQL), de modo que el dinero del dashboard se compara contra las cifras
 * que da Finanzas y el detalle POS contra las mismas ventas.
 * <p>
 * Dataset (todo 2026; periodo 10-19 sep, anterior 31 ago-9 sep, tendencia abr-sep):
 * <ul>
 *   <li>Miraflores (sitio 1, location 10): histórico jun/jul, POS desde su primera venta real (15 ago = go-live).</li>
 *   <li>Majadas (sitio 2, sin location): solo histórico.</li>
 *   <li>Zona 10 (sitio 3, location 11): go-live forzado (override) el 12 sep; histórico antes de esa fecha.</li>
 *   <li>Sin ventas (sitio 5, location 14): incluido pero sin ventas.</li>
 *   <li>Sitio 4 (location 12) excluido de reportes y location 99 sin sitio: sus ventas nunca deben contar.</li>
 * </ul>
 */
class KioskSalesDashboardServiceTest {

    private static final DateRange RANGE = new DateRange(d(9, 10), d(9, 19));
    private static final long MIRAFLORES = 1L;
    private static final long MAJADAS = 2L;
    private static final long ZONA_10 = 3L;
    private static final long EXCLUDED = 4L;
    private static final long SIN_VENTAS = 5L;
    private static final String SITE_NOT_AVAILABLE = "El kiosko seleccionado no existe o no está incluido en los reportes.";

    private KioskSaleRepository kioskSaleRepository;
    private KioskSaleItemRepository kioskSaleItemRepository;
    private KioskPosSalesAggregateRepository posAggregateRepository;
    private KioskDailySalesHistRepository histRepository;
    private KioskSiteRepository kioskSiteRepository;
    private LocationRepository locationRepository;
    private ProductRepository productRepository;
    private KioskSalesSourceResolver resolver;
    private SalesSourceLoader loader;
    private SalesDashboardCache cache;
    private KioskSalesDashboardService service;

    private List<KioskSiteEntity> includedSites;

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @BeforeEach
    void setUp() {
        kioskSaleRepository = mock(KioskSaleRepository.class);
        kioskSaleItemRepository = mock(KioskSaleItemRepository.class);
        posAggregateRepository = mock(KioskPosSalesAggregateRepository.class);
        histRepository = mock(KioskDailySalesHistRepository.class);
        kioskSiteRepository = mock(KioskSiteRepository.class);
        locationRepository = mock(LocationRepository.class);
        productRepository = mock(ProductRepository.class);
        resolver = spy(new KioskSalesSourceResolver(posAggregateRepository, histRepository));
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new SalesDashboardCache(manager, mock(PlatformTransactionManager.class));
        loader = new SalesSourceLoader(kioskSaleRepository, mock(OnlineSaleRepository.class),
                mock(ProductionOrderRepository.class), productRepository, mock(CustomerAccountService.class));
        service = new KioskSalesDashboardService(
                loader, kioskSaleItemRepository, locationRepository, kioskSiteRepository, resolver, cache);

        stubBaseDataset();
    }

    private void stubBaseDataset() {
        KioskSiteEntity zona10 = site(ZONA_10, "Zona 10", 11L);
        zona10.setPosGoLiveOverride(d(9, 12));
        includedSites = List.of(
                site(MIRAFLORES, "Miraflores", 10L),
                site(MAJADAS, "Majadas historico", null),
                zona10,
                site(SIN_VENTAS, "Sin ventas", 14L));
        // El sitio 4 está excluido de reportes: el repositorio (excludeFromReports = false) no lo devuelve.
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(includedSites);

        // Nombres de location distintos a los del sitio: el dashboard debe mostrar el nombre del SITIO.
        List<LocationEntity> locations = List.of(
                LocationEntity.builder().id(10L).code("K10").name("Miraflores POS").build(),
                LocationEntity.builder().id(11L).code("K11").name("Zona 10 POS").build(),
                LocationEntity.builder().id(14L).code("K14").name("Sin ventas POS").build());
        when(locationRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return locations.stream().filter(l -> ids.contains(l.getId())).toList();
        });

        List<KioskSaleHeaderRow> headers = List.of(
                header(1, 10, d(9, 10), "300.00", "EFECTIVO", "COMPLETED", false),
                header(2, 10, d(9, 15), "100.00", "TARJETA", "COMPLETED", false),
                header(3, 10, d(9, 5), "200.00", "EFECTIVO", "COMPLETED", false),   // periodo anterior
                header(4, 10, d(8, 15), "60.00", "EFECTIVO", "COMPLETED", false),   // primera venta real = go-live
                header(5, 10, d(9, 12), "9999.00", "EFECTIVO", "CANCELLED", false),
                header(6, 10, d(9, 12), "9999.00", "EFECTIVO", "VOID", false),
                header(7, 10, d(9, 12), "9999.00", "EFECTIVO", "COMPLETED", true),
                header(8, 11, d(9, 11), "500.00", "EFECTIVO", "COMPLETED", false),  // antes del override: manda el histórico
                header(9, 11, d(9, 12), "90.00", "EFECTIVO", "COMPLETED", false),
                header(10, 11, d(9, 18), "110.00", "TARJETA", "COMPLETED", false),
                header(11, 12, d(9, 14), "777.00", "EFECTIVO", "COMPLETED", false), // sitio excluido de reportes
                header(12, 99, d(9, 14), "888.00", "EFECTIVO", "COMPLETED", false)); // location sin sitio
        List<KioskSaleItemRow> items = List.of(
                item(1, 1L, "CIN-01", "Cincho T.42", "2", "250.00"),
                item(1, 90L, "SUM-BOLSA", "Bolsa", "1", "50.00"),
                item(2, 1L, "CIN-01", "Cincho T.44", "1", "100.00"),
                item(3, 1L, "CIN-01", "Cincho T.42", "1", "200.00"),
                item(8, 3L, "BIL-01", "Billetera", "9", "500.00"),
                item(9, 2L, "CIN-02", "Faja T.30", "3", "90.00"),
                item(10, 2L, "CIN-02", "Faja T.32", "1", "110.00"),
                item(11, 3L, "BIL-01", "Billetera", "9", "777.00"),
                item(12, 3L, "BIL-01", "Billetera", "9", "888.00"));
        KioskSalesTestFixtures.stubPosSales(kioskSaleRepository, posAggregateRepository, headers);
        KioskSalesTestFixtures.stubPosItems(kioskSaleItemRepository, headers, items);

        KioskSalesTestFixtures.stubHist(histRepository, List.of(
                hist(MIRAFLORES, d(6, 10), "150.00"),
                hist(MIRAFLORES, d(7, 20), "250.00"),
                hist(MIRAFLORES, d(8, 20), "999.00"),   // solapa con el POS (>= go-live): se ignora
                hist(MIRAFLORES, d(9, 12), "888.00"),   // idem
                hist(MAJADAS, d(5, 12), "80.00"),
                hist(MAJADAS, d(8, 31), "40.00"),
                hist(MAJADAS, d(9, 12), "60.00"),
                hist(MAJADAS, d(9, 15), "40.00"),
                hist(ZONA_10, d(9, 5), "30.00"),
                hist(ZONA_10, d(9, 11), "70.00"),
                hist(ZONA_10, d(9, 12), "999.00"),      // >= override: se ignora
                hist(EXCLUDED, d(9, 14), "555.00")));   // sitio excluido: nunca se pide al resolver
    }

    // ------------------------------------------------------- dinero (fuente Finanzas)

    @Test
    void totalIsTheFinanceNumberHistBeforeGoLivePlusPosAfterIncludingPackaging() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, null, null);

        // La cifra de Finanzas kioscos para esos días y sitios: lo que da el resolver con los mismos sitios.
        Map<Long, LocalDate> goLive = resolver.goLiveEffective(includedSites);
        Map<Long, SiteSales> finance = resolver.resolve(includedSites, RANGE.from(), RANGE.to(), goLive);
        BigDecimal financeTotal = KioskSalesTestFixtures.financeTotal(finance, RANGE.from(), RANGE.to());

        // Miraflores 400 (POS) + Majadas 100 (histórico) + Zona 10 270 (70 hist antes del override + 90 + 110 POS).
        assertThat(financeTotal).isEqualByComparingTo("770.00");
        assertThat(result.getKpis().getTotalAmount()).isEqualByComparingTo(financeTotal);
        assertThat(result.getKpis().getTotalAmount().toPlainString()).isEqualTo("770.00");
        // El empaque va dentro del total (la bolsa de 50 del ticket 1), igual que en Finanzas.
        assertThat(result.getKpis().getPackagingAmount()).isEqualByComparingTo("50.00");
        // Cada sitio coincide con lo que Finanzas calcula para ese sitio.
        for (BreakdownRow row : result.getBreakdowns().get("byKiosk")) {
            SiteSales siteSales = finance.get(Long.valueOf(row.getKey()));
            assertThat(row.getAmount()).isEqualByComparingTo(siteSales.totalBetween(RANGE.from(), RANGE.to()));
        }
        // Las ventas del sitio excluido (777 POS, 555 hist) y de la location sin sitio (888) no suman: el total es
        // exactamente el de Finanzas (770).
    }

    @Test
    void dailySeriesCarriesFinanceMoneyPerDayAndPosTicketCounts() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, null, null);

        List<DailyPoint> series = result.getDailySeries();
        assertThat(series).hasSize(10);
        assertThat(series).extracting(DailyPoint::getDate).first().isEqualTo(d(9, 10));
        assertThat(series).extracting(p -> p.getAmount().toPlainString()).containsExactly(
                "300.00",  // 10: POS Miraflores
                "70.00",   // 11: histórico de Zona 10 (el ticket 8 del POS no cuenta antes del override)
                "150.00",  // 12: 60 histórico Majadas + 90 POS Zona 10
                "0.00", "0.00",   // 13, 14 (las ventas 11 y 12 de otro sitio / sin sitio no cuentan)
                "140.00",  // 15: 100 POS Miraflores + 40 histórico Majadas
                "0.00", "0.00",
                "110.00",  // 18: POS Zona 10
                "0.00");
        // Los tickets son solo POS: el día 11 tiene dinero (histórico) pero 0 tickets.
        assertThat(series).extracting(DailyPoint::getCount).containsExactly(1, 0, 1, 0, 0, 1, 0, 0, 1, 0);
        BigDecimal seriesSum = series.stream().map(DailyPoint::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(seriesSum).isEqualByComparingTo(result.getKpis().getTotalAmount());
    }

    @Test
    void previousPeriodGrowthAndSixMonthTrendComeFromTheFinanceSource() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, null, null);
        SourceKpis kpis = result.getKpis();

        // 31 ago-9 sep: Miraflores POS 200 (5 sep) + Majadas hist 40 (31 ago) + Zona 10 hist 30 (5 sep).
        assertThat(result.getPreviousStartDate()).isEqualTo(d(8, 31));
        assertThat(result.getPreviousEndDate()).isEqualTo(d(9, 9));
        assertThat(kpis.getPreviousTotalAmount()).isEqualByComparingTo("270.00");
        assertThat(kpis.getGrowthPercent()).isEqualByComparingTo("185.2"); // (770 - 270) / 270

        assertThat(result.getMonthlyTrend()).extracting(TrendPoint::getMonth).containsExactly(4, 5, 6, 7, 8, 9);
        assertThat(result.getMonthlyTrend()).extracting(TrendPoint::getYear).containsOnly(2026);
        assertThat(result.getMonthlyTrend()).extracting(TrendPoint::getLabel)
                .containsExactly("abr", "may", "jun", "jul", "ago", "sep");
        // may: hist Majadas 80; jun/jul: hist Miraflores; ago: POS Miraflores 60 + hist Majadas 40;
        // sep: todo el mes (200 + 300 + 100 Miraflores, 60 + 40 Majadas, 30 + 70 + 90 + 110 Zona 10).
        assertThat(result.getMonthlyTrend()).extracting(p -> p.getAmount().toPlainString())
                .containsExactly("0.00", "80.00", "150.00", "250.00", "100.00", "1000.00");
    }

    @Test
    void siteFilterNarrowsPreviousPeriodAndTrend() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, MAJADAS, null);

        assertThat(result.getKpis().getPreviousTotalAmount()).isEqualByComparingTo("40.00");
        assertThat(result.getKpis().getGrowthPercent()).isEqualByComparingTo("150.0"); // (100 - 40) / 40
        assertThat(result.getMonthlyTrend()).extracting(p -> p.getAmount().toPlainString())
                .containsExactly("0.00", "80.00", "0.00", "0.00", "40.00", "100.00");
    }

    @Test
    void todayAmountComesFromTheFinanceSourceIncludingHistoricalDays() throws Exception {
        LocalDate today = SalesDashboardSupport.today();
        DateRange current = new DateRange(today.minusDays(9), today);
        // Un sitio POS con una venta de 300 hoy y un sitio histórico con 40 hoy.
        List<KioskSaleHeaderRow> headers = List.of(header(1, 10, today, "300.00", "EFECTIVO", "COMPLETED", false));
        KioskSalesTestFixtures.stubPosSales(kioskSaleRepository, posAggregateRepository, headers);
        KioskSalesTestFixtures.stubPosItems(kioskSaleItemRepository, headers,
                List.of(item(1, 1L, "CIN-01", "Cincho", "2", "300.00")));
        KioskSalesTestFixtures.stubHist(histRepository, List.of(hist(MAJADAS, today, "40.00")));

        SalesSourceDetailResponse result = service.build(current, null, null);

        assertThat(result.getKpis().getDailyAmount()).isEqualByComparingTo("340.00");
        DailyPoint last = result.getDailySeries().get(9);
        assertThat(last.getDate()).isEqualTo(today);
        assertThat(last.getAmount()).isEqualByComparingTo("340.00");
        assertThat(last.getCount()).isEqualTo(1);

        // Si hoy cae fuera del rango, el monto de hoy es 0.
        SalesSourceDetailResponse past = service.build(new DateRange(today.minusDays(20), today.minusDays(10)), null, null);
        assertThat(past.getKpis().getDailyAmount()).isEqualByComparingTo("0");
        // El dataset base (fechas de 2026 ya pasadas) tampoco tiene hoy en su rango.
        stubBaseDataset();
        assertThat(service.build(RANGE, null, null).getKpis().getDailyAmount().toPlainString()).isEqualTo("0.00");
    }

    // ---------------------------------------------------------- detalle solo POS

    @Test
    void posDetailExcludesSalesBeforeGoLiveAndLocationsWithoutAnIncludedSite() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, null, null);
        SourceKpis kpis = result.getKpis();

        // Tickets: 1 y 2 (Miraflores) y 9 y 10 (Zona 10 desde su go-live del 12 sep). Fuera: 8 (Zona 10 el 11 sep,
        // antes del go-live: ese día manda el histórico), 11 (sitio excluido), 12 (location sin sitio) y las
        // ventas canceladas / anuladas / de prueba (5, 6, 7).
        assertThat(result.getRecentSales()).extracting(SaleRow::getId).containsExactly(10L, 2L, 9L, 1L);
        assertThat(kpis.getSalesCount()).isEqualTo(4);
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("7");
        assertThat(kpis.getProductAmount().toPlainString()).isEqualTo("550.00");
        assertThat(kpis.getPackagingAmount().toPlainString()).isEqualTo("50.00");
        // El ranking solo ve productos terminados de esos tickets (sin la "Billetera" de las ventas excluidas).
        assertThat(result.getTopProducts()).extracting(ProductRank::getProductName).containsExactly("Faja", "Cincho");
        assertThat(result.getTopProducts().get(0).getUnits()).isEqualByComparingTo("4");
        assertThat(result.getTopProducts().get(0).getAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getTopProducts().get(1).getUnits()).isEqualByComparingTo("3");
        assertThat(result.getTopProducts().get(1).getAmount()).isEqualByComparingTo("350.00");
        // La parte "party" de las ventas recientes es el nombre del sitio.
        assertThat(result.getRecentSales()).extracting(SaleRow::getParty)
                .containsExactly("Zona 10", "Miraflores", "Zona 10", "Miraflores");
        assertThat(result.getRecentSales().get(3).getProductLabel()).isEqualTo("Cincho");
        assertThat(result.getRecentSales().get(3).getQuantity()).isEqualByComparingTo("2");
        // La parte POS del total de Finanzas es exactamente lo detallado: 600 de 770.
        assertThat(kpis.getProductAmount().add(kpis.getPackagingAmount())).isEqualByComparingTo("600.00");
    }

    @Test
    void historicalAmountIsTotalMinusPosProductAndPackagingAndSplitAddsUp() throws Exception {
        SourceKpis kpis = service.build(RANGE, null, null).getKpis();

        assertThat(kpis.getTotalAmount().toPlainString()).isEqualTo("770.00");
        // 770 - 550 (producto POS) - 50 (empaque POS) = 170 = 100 de Majadas + 70 de Zona 10 (11 sep) sin tickets.
        assertThat(kpis.getHistoricalAmount().toPlainString()).isEqualTo("170.00");
        assertThat(kpis.getShippingAmount().toPlainString()).isEqualTo("0.00");
        assertSplitAddsUp(kpis);

        // La invariante vale con cualquier filtro de kiosko.
        for (long siteId : new long[]{MIRAFLORES, MAJADAS, ZONA_10, SIN_VENTAS}) {
            assertSplitAddsUp(service.build(RANGE, siteId, null).getKpis());
        }
        assertThat(service.build(RANGE, MIRAFLORES, null).getKpis().getHistoricalAmount().toPlainString())
                .isEqualTo("0.00");
        assertThat(service.build(RANGE, ZONA_10, null).getKpis().getHistoricalAmount().toPlainString())
                .isEqualTo("70.00");
        assertThat(service.build(RANGE, MAJADAS, null).getKpis().getHistoricalAmount().toPlainString())
                .isEqualTo("100.00");
    }

    @Test
    void averageTicketIsOnPosBasisNotTotalOverTickets() throws Exception {
        SourceKpis all = service.build(RANGE, null, null).getKpis();

        // (producto + empaque) / tickets POS = 600 / 4; NO 770 / 4 = 192.50 (el histórico no tiene tickets).
        assertThat(all.getAvgTicket().toPlainString()).isEqualTo("150.00");
        assertThat(service.build(RANGE, MIRAFLORES, null).getKpis().getAvgTicket()).isEqualByComparingTo("200.00");
        assertThat(service.build(RANGE, ZONA_10, null).getKpis().getAvgTicket()).isEqualByComparingTo("100.00");
        // Sin tickets POS el promedio es 0.00 aunque haya dinero histórico.
        assertThat(service.build(RANGE, MAJADAS, null).getKpis().getAvgTicket().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void paymentMixIsPosOnlyAndItsShareIsOverTheProductPlusPackagingBase() throws Exception {
        List<BreakdownRow> payments = service.build(RANGE, null, null).getBreakdowns().get("byPaymentMethod");

        // EFECTIVO: 300 + 90; TARJETA: 100 + 110. Base POS = 600 (no los 770 que incluyen histórico).
        assertThat(payments).extracting(BreakdownRow::getKey).containsExactly("EFECTIVO", "TARJETA");
        assertThat(payments).extracting(r -> r.getAmount().toPlainString()).containsExactly("390.00", "210.00");
        assertThat(payments).extracting(BreakdownRow::getCount).containsExactly(2, 2);
        assertThat(payments).extracting(r -> r.getSharePercent().toPlainString()).containsExactly("65.0", "35.0");
    }

    // ------------------------------------------------------------ filtro por kiosko

    @Test
    void siteFilterRestrictsMoneyAndPosDetailButNotTheOptionsList() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, MIRAFLORES, null);
        SourceKpis kpis = result.getKpis();

        assertThat(kpis.getTotalAmount().toPlainString()).isEqualTo("400.00");
        assertThat(kpis.getPreviousTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(kpis.getGrowthPercent()).isEqualByComparingTo("100.0");
        assertThat(kpis.getSalesCount()).isEqualTo(2);
        assertThat(kpis.getProductAmount()).isEqualByComparingTo("350.00");
        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("50.00");
        assertThat(kpis.getHistoricalAmount()).isEqualByComparingTo("0");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("3");
        assertThat(result.getRecentSales()).extracting(SaleRow::getId).containsExactly(2L, 1L);
        assertThat(result.getTopProducts()).extracting(ProductRank::getProductName).containsExactly("Cincho");
        assertThat(result.getBreakdowns().get("byKiosk")).extracting(BreakdownRow::getKey).containsExactly("1");
        assertThat(result.getBreakdowns().get("byKiosk").get(0).getSharePercent()).isEqualByComparingTo("100.0");
        assertThat(result.getBreakdowns().get("byPaymentMethod")).extracting(r -> r.getSharePercent().toPlainString())
                .containsExactly("75.0", "25.0");
        assertThat(result.getMonthlyTrend()).extracting(p -> p.getAmount().toPlainString())
                .containsExactly("0.00", "0.00", "150.00", "250.00", "60.00", "600.00");
        // La lista del selector no se filtra: sigue trayendo todos los sitios con venta en el periodo.
        assertThat(result.getKioskOptions()).extracting(KioskOption::getSiteId).containsExactly(MAJADAS, MIRAFLORES, ZONA_10);
    }

    @Test
    void legacyKioskLocationIdIsTranslatedToItsSite() throws Exception {
        SalesSourceDetailResponse byLocation = service.build(RANGE, null, 11L);
        SalesSourceDetailResponse bySite = service.build(RANGE, ZONA_10, null);

        assertThat(byLocation).isEqualTo(bySite);
        assertThat(byLocation.getKpis().getTotalAmount().toPlainString()).isEqualTo("270.00");
        assertThat(byLocation.getKpis().getSalesCount()).isEqualTo(2);
        assertThat(byLocation.getKpis().getProductAmount()).isEqualByComparingTo("200.00");
        assertThat(byLocation.getKpis().getHistoricalAmount()).isEqualByComparingTo("70.00");
        assertThat(byLocation.getKpis().getPreviousTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(byLocation.getKpis().getGrowthPercent()).isEqualByComparingTo("800.0");
        assertThat(byLocation.getRecentSales()).extracting(SaleRow::getId).containsExactly(10L, 9L);
    }

    @Test
    void siteIdWinsWhenBothFiltersArrive() throws Exception {
        SalesSourceDetailResponse both = service.build(RANGE, MIRAFLORES, 11L);

        assertThat(both).isEqualTo(service.build(RANGE, MIRAFLORES, null));
        assertThat(both.getKpis().getTotalAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void unknownOrExcludedSiteIsRejectedWithABusinessException() {
        assertThatThrownBy(() -> service.build(RANGE, 999L, null))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        // El sitio 4 existe pero está excluido de reportes: el repositorio no lo devuelve.
        assertThatThrownBy(() -> service.build(RANGE, EXCLUDED, null))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        // Legacy: location de un sitio excluido y location sin sitio.
        assertThatThrownBy(() -> service.build(RANGE, null, 12L))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        assertThatThrownBy(() -> service.build(RANGE, null, 99L))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        // Con siteId inexistente manda siteId aunque la location sí exista.
        assertThatThrownBy(() -> service.build(RANGE, 999L, 10L))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
    }

    @Test
    void requestedSiteWithoutSalesIsStillOfferedInTheOptions() throws Exception {
        SalesSourceDetailResponse unfiltered = service.build(RANGE, null, null);
        assertThat(unfiltered.getKioskOptions()).extracting(KioskOption::getSiteId)
                .doesNotContain(SIN_VENTAS);
        assertThat(unfiltered.getBreakdowns().get("byKiosk")).extracting(BreakdownRow::getKey).doesNotContain("5");

        SalesSourceDetailResponse filtered = service.build(RANGE, SIN_VENTAS, null);

        assertThat(filtered.getKpis().getTotalAmount().toPlainString()).isEqualTo("0.00");
        assertThat(filtered.getKpis().getSalesCount()).isZero();
        assertThat(filtered.getBreakdowns().get("byKiosk")).isEmpty();
        assertThat(filtered.getKioskOptions()).extracting(KioskOption::getKioskName)
                .containsExactly("Majadas historico", "Miraflores", "Sin ventas", "Zona 10");
    }

    // ----------------------------------------------------- sitio histórico (sin POS)

    @Test
    void historicalOnlySiteHasMoneyButNoPosDetail() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, MAJADAS, null);
        SourceKpis kpis = result.getKpis();

        assertThat(kpis.getTotalAmount().toPlainString()).isEqualTo("100.00");
        assertThat(kpis.getHistoricalAmount().toPlainString()).isEqualTo("100.00");
        assertThat(kpis.getProductAmount().toPlainString()).isEqualTo("0.00");
        assertThat(kpis.getPackagingAmount().toPlainString()).isEqualTo("0.00");
        assertThat(kpis.getSalesCount()).isZero();
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("0");
        assertThat(kpis.getAvgTicket().toPlainString()).isEqualTo("0.00");
        assertSplitAddsUp(kpis);
        assertThat(result.getTopProducts()).isEmpty();
        assertThat(result.getRecentSales()).isEmpty();
        assertThat(result.getBreakdowns().get("byPaymentMethod")).isEmpty();
        assertThat(result.getDailySeries()).extracting(DailyPoint::getCount).containsOnly(0);
        assertThat(result.getDailySeries()).extracting(p -> p.getAmount().toPlainString()).containsExactly(
                "0.00", "0.00", "60.00", "0.00", "0.00", "40.00", "0.00", "0.00", "0.00", "0.00");

        List<BreakdownRow> byKiosk = result.getBreakdowns().get("byKiosk");
        assertThat(byKiosk).hasSize(1);
        assertThat(byKiosk.get(0).getKey()).isEqualTo("2");
        assertThat(byKiosk.get(0).getLabel()).isEqualTo("Majadas historico");
        assertThat(byKiosk.get(0).getCount()).isZero();
        assertThat(byKiosk.get(0).getAmount().toPlainString()).isEqualTo("100.00");
        assertThat(byKiosk.get(0).getSharePercent()).isEqualByComparingTo("100.0");

        // No hay ninguna location POS que detallar: ni cabeceras ni ítems POS se consultan.
        verify(kioskSaleRepository, never()).findHeaderRowsBySaleDateBetween(any(), any());
        verify(kioskSaleItemRepository, never()).findRowsBySaleDateBetween(any(), any());
    }

    // ------------------------------------------------------------------- formas

    @Test
    void byKioskHasOneRowPerSiteAndKioskOptionsCarrySiteAndLocationIds() throws Exception {
        SalesSourceDetailResponse result = service.build(RANGE, null, null);

        // Una fila por SITIO con venta distinta de cero (orden: importe desc). Sin el sitio "Sin ventas".
        List<BreakdownRow> byKiosk = result.getBreakdowns().get("byKiosk");
        assertThat(byKiosk).extracting(BreakdownRow::getKey).containsExactly("1", "3", "2");
        assertThat(byKiosk).extracting(BreakdownRow::getLabel).containsExactly("Miraflores", "Zona 10", "Majadas historico");
        assertThat(byKiosk).extracting(r -> r.getAmount().toPlainString()).containsExactly("400.00", "270.00", "100.00");
        // count = tickets POS del sitio en el periodo (0 si es histórico).
        assertThat(byKiosk).extracting(BreakdownRow::getCount).containsExactly(2, 2, 0);
        // sharePercent sobre el total (con histórico): 400/770, 270/770, 100/770.
        assertThat(byKiosk).extracting(r -> r.getSharePercent().toPlainString()).containsExactly("51.9", "35.1", "13.0");
        assertThat(result.getBreakdowns()).containsOnlyKeys("byKiosk", "byPaymentMethod");

        // kioskOptions: sitios con venta ≠ 0 en el periodo, por nombre; kioskId = location POS (null si histórico).
        List<KioskOption> options = result.getKioskOptions();
        assertThat(options).extracting(KioskOption::getSiteId).containsExactly(MAJADAS, MIRAFLORES, ZONA_10);
        assertThat(options).extracting(KioskOption::getKioskId).containsExactly(null, 10L, 11L);
        assertThat(options).extracting(KioskOption::getKioskCode).containsExactly("", "K10", "K11");
        assertThat(options).extracting(KioskOption::getKioskName)
                .containsExactly("Majadas historico", "Miraflores", "Zona 10");

        assertThat(result.getChannel()).isEqualTo("KIOSKO");
        assertThat(result.getLabel()).isEqualTo("Kioskos");
        assertThat(result.getStartDate()).isEqualTo(RANGE.from());
        assertThat(result.getEndDate()).isEqualTo(RANGE.to());
    }

    // ------------------------------------------------------------ consultas y robustez

    @Test
    void cacheMissIssuesOneQueryPerSourceAndComputesTheGoLiveOnce() throws Exception {
        service.build(RANGE, null, null);

        LocalDate loadFrom = SalesDashboardSupport.loadFrom(RANGE);
        verify(kioskSiteRepository, times(1)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        verify(kioskSiteRepository, never()).findByLocationId(anyLong());
        // El go-live se calcula una vez y se pasa al resolver (el resolver no lo recalcula).
        verify(resolver, times(1)).goLiveEffective(anyCollection());
        verify(resolver, times(1)).resolve(anyCollection(), eq(loadFrom), eq(RANGE.to()), anyMap());
        verify(resolver, never()).resolve(anyCollection(), any(), any());
        verify(posAggregateRepository, times(1)).findFirstRealSaleDateByLocation(anyCollection());
        verify(posAggregateRepository, times(1))
                .sumRealSalesByLocationAndDate(anyCollection(), eq(loadFrom), eq(RANGE.to()));
        verify(histRepository, times(1)).findBySitesAndRange(anyCollection(), eq(loadFrom), eq(RANGE.to()));
        // El detalle POS solo carga el periodo actual.
        verify(kioskSaleRepository, times(1)).findHeaderRowsBySaleDateBetween(RANGE.from(), RANGE.to());
        verify(kioskSaleItemRepository, times(1)).findRowsBySaleDateBetween(RANGE.from(), RANGE.to());
        verify(locationRepository, times(1)).findAllById(anyCollection());
        verify(productRepository, never()).findAllById(anyCollection());

        // 7 consultas en total: sitios, primera venta, histórico, agregados POS, cabeceras, ítems y locations.
        assertThat(queryCount()).isEqualTo(7);
    }

    @Test
    void filteredRequestStillResolvesTheFinanceSourceOnlyOnce() throws Exception {
        service.build(RANGE, ZONA_10, null);

        verify(resolver, times(1)).goLiveEffective(anyCollection());
        verify(resolver, times(1)).resolve(anyCollection(), any(), any(), anyMap());
        assertThat(queryCount()).isEqualTo(7);
    }

    @Test
    void historicalOnlyRequestSkipsPosDetailQueries() throws Exception {
        service.build(RANGE, MAJADAS, null);

        // sitios, primera venta, histórico, agregados POS y locations del selector: sin cabeceras ni ítems.
        assertThat(queryCount()).isEqualTo(5);
    }

    @Test
    void getDashboardCachesPerFilterAndRaisesBusinessExceptionThroughTheCache() throws Exception {
        SalesSourceDetailResponse first = service.getDashboard(RANGE.from(), RANGE.to(), MIRAFLORES, null, false);
        SalesSourceDetailResponse second = service.getDashboard(RANGE.from(), RANGE.to(), MIRAFLORES, null, false);

        assertThat(second).isSameAs(first);
        verify(kioskSiteRepository, times(1)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // Otro sitio es otra entrada; la misma cifra como location POS legacy también (clave distinta).
        SalesSourceDetailResponse zona = service.getDashboard(RANGE.from(), RANGE.to(), ZONA_10, null, false);
        SalesSourceDetailResponse zonaByLocation = service.getDashboard(RANGE.from(), RANGE.to(), null, 11L, false);
        assertThat(zona.getKpis().getTotalAmount()).isEqualByComparingTo("270.00");
        assertThat(zonaByLocation).isEqualTo(zona).isNotSameAs(zona);
        verify(kioskSiteRepository, times(3)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // refresh=true descarta la entrada y recalcula.
        SalesSourceDetailResponse refreshed = service.getDashboard(RANGE.from(), RANGE.to(), MIRAFLORES, null, true);
        assertThat(refreshed).isNotSameAs(first).isEqualTo(first);
        verify(kioskSiteRepository, times(4)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // Un sitio inexistente llega como BusinessException (no como excepción del caché) y no se cachea.
        assertThatThrownBy(() -> service.getDashboard(RANGE.from(), RANGE.to(), 999L, null, false))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        assertThatThrownBy(() -> service.getDashboard(RANGE.from(), RANGE.to(), 999L, null, false))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        assertThatThrownBy(() -> service.getDashboard(RANGE.from(), RANGE.to(), null, 99L, false))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
        verify(kioskSiteRepository, times(7)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
    }

    @Test
    void withoutIncludedSitesTheChannelIsAllZeroAndOnlyTheSiteListIsQueried() throws Exception {
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(List.of());

        SalesSourceDetailResponse result = service.build(RANGE, null, null);

        SourceKpis kpis = result.getKpis();
        assertThat(kpis.getTotalAmount().toPlainString()).isEqualTo("0.00");
        assertThat(kpis.getHistoricalAmount().toPlainString()).isEqualTo("0.00");
        assertThat(kpis.getAvgTicket().toPlainString()).isEqualTo("0.00");
        assertThat(kpis.getSalesCount()).isZero();
        assertThat(kpis.getGrowthPercent()).isEqualByComparingTo("0");
        assertThat(result.getDailySeries()).hasSize(10);
        assertThat(result.getMonthlyTrend()).hasSize(6);
        assertThat(result.getBreakdowns().get("byKiosk")).isEmpty();
        assertThat(result.getBreakdowns().get("byPaymentMethod")).isEmpty();
        assertThat(result.getKioskOptions()).isEmpty();
        assertThat(result.getRecentSales()).isEmpty();
        assertThat(result.getTopProducts()).isEmpty();
        assertThat(queryCount()).isEqualTo(1);
        // Y con un filtro, un sitio que no está en el catálogo es un error de negocio.
        assertThatThrownBy(() -> service.build(RANGE, MIRAFLORES, null))
                .isInstanceOf(BusinessException.class).hasMessage(SITE_NOT_AVAILABLE);
    }

    @Test
    void buildAllIsTheUnfilteredChannelUsedByTheConsolidated() throws Exception {
        assertThat(service.buildAll(RANGE)).isEqualTo(service.build(RANGE, null, null));
    }

    @Test
    void historicalAmountNeverGoesNegative() throws Exception {
        // La parte POS detallada (300) supera lo que informa la fuente de Finanzas (200): no debería pasar, pero el
        // histórico se corta en 0 en vez de salir negativo.
        KioskSalesSourceResolver financeSource = mock(KioskSalesSourceResolver.class);
        KioskSiteEntity site = site(MIRAFLORES, "Miraflores", 10L);
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(List.of(site));
        when(financeSource.goLiveEffective(anyCollection())).thenReturn(Map.of(MIRAFLORES, d(8, 15)));
        when(financeSource.resolve(anyCollection(), any(), any(), anyMap())).thenReturn(Map.of(MIRAFLORES,
                new SiteSales(MIRAFLORES, d(8, 15), new TreeMap<>(Map.of(d(9, 10), new BigDecimal("200.00"))), true, false)));
        List<KioskSaleHeaderRow> headers = List.of(header(1, 10, d(9, 10), "300.00", "EFECTIVO", "COMPLETED", false));
        KioskSalesTestFixtures.stubPosSales(kioskSaleRepository, posAggregateRepository, headers);
        KioskSalesTestFixtures.stubPosItems(kioskSaleItemRepository, headers,
                List.of(item(1, 1L, "CIN-01", "Cincho", "1", "300.00")));
        KioskSalesDashboardService withMockedSource = new KioskSalesDashboardService(
                loader, kioskSaleItemRepository, locationRepository, kioskSiteRepository, financeSource, cache);

        SourceKpis kpis = withMockedSource.build(RANGE, null, null).getKpis();

        assertThat(kpis.getTotalAmount().toPlainString()).isEqualTo("200.00");
        assertThat(kpis.getProductAmount().toPlainString()).isEqualTo("300.00");
        assertThat(kpis.getHistoricalAmount().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void sitesWithoutAnEffectiveGoLiveContributeNoPosDetail() throws Exception {
        // Un sitio con location pero sin go-live efectivo (ni override ni venta real) usa solo histórico: sus
        // cabeceras POS, si las hubiera, no se detallan y ni siquiera se consultan.
        KioskSalesSourceResolver financeSource = mock(KioskSalesSourceResolver.class);
        KioskSiteEntity site = site(MIRAFLORES, "Miraflores", 10L);
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(List.of(site));
        when(financeSource.goLiveEffective(anyCollection())).thenReturn(Map.of());
        when(financeSource.resolve(anyCollection(), any(), any(), anyMap())).thenReturn(Map.of(MIRAFLORES,
                new SiteSales(MIRAFLORES, null, new TreeMap<>(Map.of(d(9, 11), new BigDecimal("75.00"))), false, true)));
        List<KioskSaleHeaderRow> headers = List.of(header(1, 10, d(9, 10), "300.00", "EFECTIVO", "COMPLETED", false));
        KioskSalesTestFixtures.stubPosSales(kioskSaleRepository, posAggregateRepository, headers);
        KioskSalesDashboardService withMockedSource = new KioskSalesDashboardService(
                loader, kioskSaleItemRepository, locationRepository, kioskSiteRepository, financeSource, cache);

        SalesSourceDetailResponse result = withMockedSource.build(RANGE, null, null);

        assertThat(result.getKpis().getTotalAmount().toPlainString()).isEqualTo("75.00");
        assertThat(result.getKpis().getHistoricalAmount().toPlainString()).isEqualTo("75.00");
        assertThat(result.getKpis().getSalesCount()).isZero();
        assertThat(result.getRecentSales()).isEmpty();
        verify(kioskSaleRepository, never()).findHeaderRowsBySaleDateBetween(any(), any());
    }

    /** Número de consultas a base de datos que hizo el servicio (repositorios que consulta, uno por interacción). */
    private int queryCount() {
        return Mockito.mockingDetails(kioskSiteRepository).getInvocations().size()
                + Mockito.mockingDetails(posAggregateRepository).getInvocations().size()
                + Mockito.mockingDetails(histRepository).getInvocations().size()
                + Mockito.mockingDetails(kioskSaleRepository).getInvocations().size()
                + Mockito.mockingDetails(kioskSaleItemRepository).getInvocations().size()
                + Mockito.mockingDetails(locationRepository).getInvocations().size()
                + Mockito.mockingDetails(productRepository).getInvocations().size();
    }

    /** Invariante del canal: producto + empaque + envío (0) + histórico == total. */
    private static void assertSplitAddsUp(SourceKpis kpis) {
        assertThat(kpis.getProductAmount().add(kpis.getPackagingAmount()).add(kpis.getShippingAmount())
                .add(kpis.getHistoricalAmount())).isEqualByComparingTo(kpis.getTotalAmount());
    }
}
