package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.CategoryRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.SiteRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosSalesAggregateRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.header;
import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.hist;
import static com.fossiles.fossilescorebackend.application.service.KioskSalesTestFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Mapa de calor de kioscos sobre la fuente de Finanzas kioscos. Se usa el {@link KioskSalesSourceResolver} real con
 * repositorios simulados (que reproducen el SQL), de modo que cada cifra se compara contra lo que da el resolver; las
 * pruebas de forma (origen, negativos, fuera de ventana) usan un resolver simulado con {@link SiteSales} a mano.
 * <p>
 * Dataset base (todo 2026; periodo 10-19 sep, anterior 31 ago-9 sep):
 * <ul>
 *   <li>Miraflores (1, location 10, "A"): histórico jun/jul, POS desde su primera venta real (15 ago = go-live).</li>
 *   <li>Majadas (2, sin location, " b "): solo histórico.</li>
 *   <li>Zona 10 (3, location 11, "c"): go-live forzado el 12 sep; histórico antes de esa fecha.</li>
 *   <li>Excluido (4, location 12): excluido de reportes. Location 99 sin sitio. Sus ventas nunca deben contar.</li>
 *   <li>Sin ventas (5, location 14, "A"): incluido pero sin ventas en ningún periodo.</li>
 *   <li>Pradera (6, "D"), aurora (7, "   "), Terraza (10, null): sin categoría; histórico.</li>
 *   <li>Cierre (8, "B"): solo vendió en el periodo anterior. antigua (9, "A"): empata con Miraflores.</li>
 *   <li>Cero (11, "C"): solo una fila histórica de 0.00 (hay dato pero no hay venta).</li>
 * </ul>
 */
class KioskHeatmapServiceTest {

    private static final DateRange RANGE = new DateRange(d(9, 10), d(9, 19));
    private static final DateRange PREVIOUS = new DateRange(d(8, 31), d(9, 9));
    private static final long MIRAFLORES = 1L;
    private static final long MAJADAS = 2L;
    private static final long ZONA_10 = 3L;
    private static final long EXCLUDED = 4L;
    private static final long SIN_VENTAS = 5L;
    private static final long PRADERA = 6L;
    private static final long AURORA = 7L;
    private static final long CIERRE = 8L;
    private static final long ANTIGUA = 9L;
    private static final long TERRAZA = 10L;
    private static final long CERO = 11L;
    private static final String MAX_DAYS_MESSAGE = "El mapa de calor admite un máximo de 400 días.";

    private KioskSaleRepository kioskSaleRepository;
    private KioskSaleItemRepository kioskSaleItemRepository;
    private KioskPosSalesAggregateRepository posAggregateRepository;
    private KioskDailySalesHistRepository histRepository;
    private KioskSiteRepository kioskSiteRepository;
    private KioskSalesSourceResolver resolver;
    private SimpleCacheManager cacheManager;
    private SalesDashboardCache cache;
    private KioskHeatmapService service;

    private List<KioskSiteEntity> includedSites;

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    private static KioskSiteEntity site(long id, String name, Long locationId, String category) {
        KioskSiteEntity site = KioskSalesTestFixtures.site(id, name, locationId);
        site.setSalesCategory(category);
        return site;
    }

    @BeforeEach
    void setUp() {
        kioskSaleRepository = mock(KioskSaleRepository.class);
        kioskSaleItemRepository = mock(KioskSaleItemRepository.class);
        posAggregateRepository = mock(KioskPosSalesAggregateRepository.class);
        histRepository = mock(KioskDailySalesHistRepository.class);
        kioskSiteRepository = mock(KioskSiteRepository.class);
        resolver = spy(new KioskSalesSourceResolver(posAggregateRepository, histRepository));
        cacheManager = (SimpleCacheManager) new CacheConfig().cacheManager();
        cacheManager.afterPropertiesSet();
        cache = new SalesDashboardCache(cacheManager, mock(PlatformTransactionManager.class));
        service = new KioskHeatmapService(kioskSiteRepository, resolver, cache);

        stubBaseDataset();
    }

    private void stubBaseDataset() {
        KioskSiteEntity zona10 = site(ZONA_10, "Zona 10", 11L, "c");
        zona10.setPosGoLiveOverride(d(9, 12));
        includedSites = List.of(
                site(MIRAFLORES, "Miraflores", 10L, "A"),
                site(MAJADAS, "Majadas historico", null, " b "),
                zona10,
                site(SIN_VENTAS, "Sin ventas", 14L, "A"),
                site(PRADERA, "Pradera", null, "D"),
                site(AURORA, "aurora", null, "   "),
                site(CIERRE, "Cierre", null, "B"),
                site(ANTIGUA, "antigua", null, "A"),
                site(TERRAZA, "Terraza", null, null),
                site(CERO, "Cero", null, "C"));
        // El sitio 4 está excluido de reportes: el repositorio (excludeFromReports = false) no lo devuelve.
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(includedSites);

        List<KioskSaleHeaderRow> headers = List.of(
                header(1, 10, d(9, 10), "300.00", "EFECTIVO", "COMPLETED", false),  // incluye 50 de empaque
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
                item(2, 1L, "CIN-01", "Cincho T.44", "1", "100.00"));
        KioskSalesTestFixtures.stubPosSales(kioskSaleRepository, posAggregateRepository, headers);
        KioskSalesTestFixtures.stubPosItems(kioskSaleItemRepository, headers, items);

        KioskSalesTestFixtures.stubHist(histRepository, List.of(
                hist(MIRAFLORES, d(6, 10), "150.00"),
                hist(MIRAFLORES, d(7, 20), "250.00"),
                hist(MIRAFLORES, d(8, 20), "999.00"),   // solapa con el POS (>= go-live): se ignora
                hist(MIRAFLORES, d(9, 12), "888.00"),   // idem
                hist(MAJADAS, d(5, 12), "80.00"),       // antes de la ventana cargada
                hist(MAJADAS, d(8, 31), "40.00"),
                hist(MAJADAS, d(9, 12), "60.00"),
                hist(MAJADAS, d(9, 15), "40.00"),
                hist(ZONA_10, d(9, 5), "30.00"),
                hist(ZONA_10, d(9, 11), "70.00"),
                hist(ZONA_10, d(9, 12), "999.00"),      // >= override: se ignora
                hist(EXCLUDED, d(9, 14), "555.00"),     // sitio excluido: nunca se pide al resolver
                hist(PRADERA, d(9, 14), "50.00"),
                hist(PRADERA, d(9, 15), "0.00"),        // dato sin venta
                hist(AURORA, d(9, 14), "50.00"),
                hist(CIERRE, d(9, 2), "80.00"),         // solo periodo anterior
                hist(ANTIGUA, d(9, 13), "400.00"),
                hist(TERRAZA, d(9, 16), "200.00"),
                hist(CERO, d(9, 14), "0.00")));
    }

    // ------------------------------------------------------- dinero (fuente Finanzas)

    @Test
    void valuesAreTheFinanceNumbersHistBeforeGoLivePlusPosAfterWithPackagingInside() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        // La cifra de Finanzas kioscos para esos días y sitios: lo que da el resolver con los mismos sitios.
        Map<Long, LocalDate> goLive = resolver.goLiveEffective(includedSites);
        Map<Long, SiteSales> finance = resolver.resolve(includedSites, PREVIOUS.from(), RANGE.to(), goLive);

        assertThat(heatmap.getSites()).isNotEmpty();
        for (SiteRow row : heatmap.getSites()) {
            SiteSales siteSales = finance.get(row.getSiteId());
            assertThat(row.getTotal()).isEqualByComparingTo(siteSales.totalBetween(RANGE.from(), RANGE.to()));
            assertThat(row.getPreviousTotal())
                    .isEqualByComparingTo(siteSales.totalBetween(PREVIOUS.from(), PREVIOUS.to()));
        }
        BigDecimal financeTotal = KioskSalesTestFixtures.financeTotal(finance, RANGE.from(), RANGE.to());
        assertThat(financeTotal).isEqualByComparingTo("1470.00");
        assertThat(sum(heatmap.getSites(), SiteRow::getTotal)).isEqualByComparingTo(financeTotal);

        // Miraflores: POS desde el go-live (15 ago). El ticket del 10 sep (300) lleva una bolsa de 50 de empaque
        // y entra COMPLETO, igual que en Finanzas; el histórico del 12 sep (888) se ignora por solapar con el POS.
        SiteRow miraflores = row(heatmap, MIRAFLORES);
        assertThat(miraflores.getTotal().toPlainString()).isEqualTo("400.00");
        assertThat(miraflores.getDaily().get(0).toPlainString()).isEqualTo("300.00");
        // Zona 10: 70 de histórico (11 sep, antes del override) + 90 + 110 POS; el ticket POS del 11 sep (500) no cuenta.
        assertThat(row(heatmap, ZONA_10).getTotal().toPlainString()).isEqualTo("270.00");
        // Majadas: solo histórico.
        assertThat(row(heatmap, MAJADAS).getTotal().toPlainString()).isEqualTo("100.00");
    }

    @Test
    void windowCoversThePreviousAndCurrentPeriodsAndBothTotalsComeFromIt() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(heatmap.getStartDate()).isEqualTo(RANGE.from());
        assertThat(heatmap.getEndDate()).isEqualTo(RANGE.to());
        assertThat(heatmap.getPreviousStartDate()).isEqualTo(d(8, 31));
        assertThat(heatmap.getPreviousEndDate()).isEqualTo(d(9, 9));

        // Periodo anterior: Miraflores POS 200 (5 sep), Majadas hist 40 (31 ago), Zona 10 hist 30 (5 sep),
        // Cierre hist 80 (2 sep).
        assertThat(row(heatmap, MIRAFLORES).getPreviousTotal().toPlainString()).isEqualTo("200.00");
        assertThat(row(heatmap, MAJADAS).getPreviousTotal().toPlainString()).isEqualTo("40.00");
        assertThat(row(heatmap, ZONA_10).getPreviousTotal().toPlainString()).isEqualTo("30.00");
        assertThat(row(heatmap, CIERRE).getPreviousTotal().toPlainString()).isEqualTo("80.00");
        assertThat(row(heatmap, ANTIGUA).getPreviousTotal().toPlainString()).isEqualTo("0.00");
        assertThat(sum(heatmap.getSites(), SiteRow::getPreviousTotal)).isEqualByComparingTo("350.00");
    }

    @Test
    void growthPercentUsesTheDashboardCriterion() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(row(heatmap, MIRAFLORES).getGrowthPercent()).isEqualByComparingTo("100.0"); // (400 - 200) / 200
        assertThat(row(heatmap, MAJADAS).getGrowthPercent()).isEqualByComparingTo("150.0");    // (100 - 40) / 40
        assertThat(row(heatmap, ZONA_10).getGrowthPercent()).isEqualByComparingTo("800.0");    // (270 - 30) / 30
        assertThat(row(heatmap, CIERRE).getGrowthPercent()).isEqualByComparingTo("-100.0");    // vendió y dejó de vender
        // Sin venta en el periodo anterior y con venta ahora: 100 (igual que SalesDashboardSupport.growthPercent).
        assertThat(row(heatmap, ANTIGUA).getGrowthPercent()).isEqualByComparingTo("100");
        for (SiteRow site : heatmap.getSites()) {
            assertThat(site.getGrowthPercent())
                    .isEqualByComparingTo(SalesDashboardSupport.growthPercent(site.getTotal(), site.getPreviousTotal()));
        }
    }

    // ------------------------------------------------------------------- días

    @Test
    void daysCoverTheCurrentRangeAndEachSiteDailyIsZeroFilledAndAligned() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(heatmap.getDays()).hasSize(10);
        assertThat(heatmap.getDays().get(0)).isEqualTo(d(9, 10));
        assertThat(heatmap.getDays().get(9)).isEqualTo(d(9, 19));
        for (int i = 1; i < heatmap.getDays().size(); i++) {
            assertThat(heatmap.getDays().get(i)).isEqualTo(heatmap.getDays().get(i - 1).plusDays(1));
        }

        assertThat(plain(row(heatmap, MIRAFLORES).getDaily())).containsExactly(
                "300.00", "0.00", "0.00", "0.00", "0.00", "100.00", "0.00", "0.00", "0.00", "0.00");
        assertThat(plain(row(heatmap, ZONA_10).getDaily())).containsExactly(
                "0.00", "70.00", "90.00", "0.00", "0.00", "0.00", "0.00", "0.00", "110.00", "0.00");
        assertThat(plain(row(heatmap, CIERRE).getDaily())).containsOnly("0.00").hasSize(10);

        // Todos alineados índice a índice con `days`, con la misma suma que el total y a 2 decimales.
        Map<Long, LocalDate> goLive = resolver.goLiveEffective(includedSites);
        Map<Long, SiteSales> finance = resolver.resolve(includedSites, PREVIOUS.from(), RANGE.to(), goLive);
        for (SiteRow site : heatmap.getSites()) {
            assertThat(site.getDaily()).hasSameSizeAs(heatmap.getDays());
            for (int i = 0; i < heatmap.getDays().size(); i++) {
                BigDecimal expected = finance.get(site.getSiteId()).daily()
                        .getOrDefault(heatmap.getDays().get(i), BigDecimal.ZERO);
                assertThat(site.getDaily().get(i)).isEqualByComparingTo(expected);
                assertThat(site.getDaily().get(i).scale()).isEqualTo(2);
            }
            assertThat(sum(site.getDaily())).isEqualByComparingTo(site.getTotal());
            assertThat(site.getTotal().scale()).isEqualTo(2);
            assertThat(site.getPreviousTotal().scale()).isEqualTo(2);
        }
    }

    @Test
    void daysWithSalesCountsOnlyDaysWithPositiveSalesInTheCurrentPeriod() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(row(heatmap, MIRAFLORES).getDaysWithSales()).isEqualTo(2);  // 10 y 15 sep
        assertThat(row(heatmap, MAJADAS).getDaysWithSales()).isEqualTo(2);     // 12 y 15 sep (el 31 ago es anterior)
        assertThat(row(heatmap, ZONA_10).getDaysWithSales()).isEqualTo(3);     // 11, 12 y 18 sep
        assertThat(row(heatmap, CIERRE).getDaysWithSales()).isZero();          // solo vendió en el periodo anterior
        // Pradera tiene una fila de 0.00 el 15 sep: es dato, pero no cuenta como día con venta.
        assertThat(row(heatmap, PRADERA).getDaysWithSales()).isEqualTo(1);
    }

    @Test
    void aSingleDayRangeHasOneDayAndTheDayBeforeAsPreviousPeriod() {
        KioskHeatmapResponse heatmap = service.build(new DateRange(d(9, 15), d(9, 15)));

        assertThat(heatmap.getDays()).containsExactly(d(9, 15));
        assertThat(heatmap.getPreviousStartDate()).isEqualTo(d(9, 14));
        assertThat(heatmap.getPreviousEndDate()).isEqualTo(d(9, 14));
        // 15 sep: Miraflores POS 100 y Majadas hist 40. El 14 sep: Pradera 50 y aurora 50 (periodo anterior).
        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId).containsExactlyInAnyOrder(
                MIRAFLORES, MAJADAS, PRADERA, AURORA);
        assertThat(plain(row(heatmap, MIRAFLORES).getDaily())).containsExactly("100.00");
        assertThat(row(heatmap, PRADERA).getTotal().toPlainString()).isEqualTo("0.00");
        assertThat(row(heatmap, PRADERA).getPreviousTotal().toPlainString()).isEqualTo("50.00");
    }

    // ---------------------------------------------------------------- sitios

    @Test
    void siteOrderIsCategoryThenTotalDescThenNameIgnoringCase() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        // A (400 empatados: antigua < Miraflores sin distinguir mayúsculas) | B (100, 0) | C | sin categoría
        // (200, y 50 empatados: aurora < Pradera aunque 'P' sea menor que 'a' en ASCII).
        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId)
                .containsExactly(ANTIGUA, MIRAFLORES, MAJADAS, CIERRE, ZONA_10, TERRAZA, AURORA, PRADERA);
        assertThat(heatmap.getSites()).extracting(SiteRow::getCategory)
                .containsExactly("A", "A", "B", "B", "C", null, null, null);
        assertThat(heatmap.getSites()).extracting(SiteRow::getName).containsExactly(
                "antigua", "Miraflores", "Majadas historico", "Cierre", "Zona 10", "Terraza", "aurora", "Pradera");
    }

    @Test
    void sitesWithNoSalesInEitherPeriodAreDropped() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        // Sin ventas (nada en ningún lado) y Cero (una fila de 0.00: hay dato pero total y anterior son 0).
        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId).doesNotContain(SIN_VENTAS, CERO);
        assertThat(heatmap.getSites()).hasSize(8);
        // Pero un sitio que solo vendió en el periodo anterior sí se queda (total 0, anterior != 0).
        SiteRow cierre = row(heatmap, CIERRE);
        assertThat(cierre.getTotal().toPlainString()).isEqualTo("0.00");
        assertThat(cierre.getPreviousTotal().toPlainString()).isEqualTo("80.00");
        // Y el sitio descartado no cuenta en su categoría.
        assertThat(category(heatmap, "C").getKioskCount()).isEqualTo(1);
    }

    @Test
    void histOnlySiteWithoutLocationIsPresentWithSourceHistAndNullLocationId() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        SiteRow majadas = row(heatmap, MAJADAS);
        assertThat(majadas.getLocationId()).isNull();
        assertThat(majadas.getSource()).isEqualTo("HIST");
        assertThat(majadas.getName()).isEqualTo("Majadas historico");
        assertThat(majadas.getTotal().toPlainString()).isEqualTo("100.00");
        assertThat(row(heatmap, ANTIGUA).getLocationId()).isNull();
        assertThat(row(heatmap, ANTIGUA).getSource()).isEqualTo("HIST");
    }

    @Test
    void sourceFollowsTheResolverAndLocationIdIsTheKioskLocation() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        // Miraflores solo tiene POS dentro de la ventana; Zona 10 mezcla histórico (antes del override) y POS.
        assertThat(row(heatmap, MIRAFLORES).getSource()).isEqualTo("POS");
        assertThat(row(heatmap, MIRAFLORES).getLocationId()).isEqualTo(10L);
        assertThat(row(heatmap, ZONA_10).getSource()).isEqualTo("MIXED");
        assertThat(row(heatmap, ZONA_10).getLocationId()).isEqualTo(11L);
        // Cierre: solo vendió en el periodo anterior, pero tiene histórico en la ventana cargada.
        assertThat(row(heatmap, CIERRE).getSource()).isEqualTo("HIST");

        Map<Long, LocalDate> goLive = resolver.goLiveEffective(includedSites);
        Map<Long, SiteSales> finance = resolver.resolve(includedSites, PREVIOUS.from(), RANGE.to(), goLive);
        for (SiteRow site : heatmap.getSites()) {
            assertThat(site.getSource()).isEqualTo(finance.get(site.getSiteId()).source());
        }
    }

    @Test
    void sourceIsNoneOnlyWithoutDataInTheCurrentRangeAndWithoutPosOrHistFlags() {
        List<KioskSiteEntity> sites = List.of(
                site(1, "Previo sin origen", null, null),
                site(2, "Actual sin banderas", null, null),
                site(3, "Previo hist", null, null),
                site(4, "Previo pos", 40L, null),
                site(5, "Mixto", 50L, null),
                site(6, "Ausente del resolver", null, null));
        Map<Long, SiteSales> sales = new LinkedHashMap<>();
        sales.put(1L, siteSales(1, false, false, d(9, 2), "80.00"));   // dato solo en el periodo anterior, sin banderas
        sales.put(2L, siteSales(2, false, false, d(9, 14), "50.00"));  // dato actual: manda SiteSales.source() (HIST)
        sales.put(3L, siteSales(3, false, true, d(9, 2), "80.00"));
        sales.put(4L, siteSales(4, true, false, d(9, 2), "80.00"));
        sales.put(5L, siteSales(5, true, true, d(9, 14), "50.00"));
        // El sitio 6 ni siquiera viene en el mapa del resolver: sin dato, se descarta.

        KioskHeatmapResponse heatmap = serviceOver(sites, sales).build(RANGE);

        Map<Long, String> sourceById = new LinkedHashMap<>();
        heatmap.getSites().forEach(s -> sourceById.put(s.getSiteId(), s.getSource()));
        assertThat(sourceById).containsEntry(1L, "NONE").containsEntry(2L, "HIST").containsEntry(3L, "HIST")
                .containsEntry(4L, "POS").containsEntry(5L, "MIXED").doesNotContainKey(6L);
    }

    // ------------------------------------------------------------- categorías

    @Test
    void categoryIsNormalizedToABCOrNull() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(row(heatmap, MIRAFLORES).getCategory()).isEqualTo("A");
        assertThat(row(heatmap, MAJADAS).getCategory()).isEqualTo("B");   // " b "
        assertThat(row(heatmap, ZONA_10).getCategory()).isEqualTo("C");   // "c"
        assertThat(row(heatmap, PRADERA).getCategory()).isNull();         // "D": no es A, B ni C
        assertThat(row(heatmap, AURORA).getCategory()).isNull();          // en blanco
        assertThat(row(heatmap, TERRAZA).getCategory()).isNull();         // null
    }

    @Test
    void siteCategoryHelperAcceptsOnlyABCIgnoringCaseAndSpaces() {
        assertThat(SalesDashboardSupport.normalizeSiteCategory("A")).isEqualTo("A");
        assertThat(SalesDashboardSupport.normalizeSiteCategory("b")).isEqualTo("B");
        assertThat(SalesDashboardSupport.normalizeSiteCategory(" b ")).isEqualTo("B");
        assertThat(SalesDashboardSupport.normalizeSiteCategory("\tC\n")).isEqualTo("C");
        assertThat(SalesDashboardSupport.normalizeSiteCategory(null)).isNull();
        assertThat(SalesDashboardSupport.normalizeSiteCategory("")).isNull();
        assertThat(SalesDashboardSupport.normalizeSiteCategory("   ")).isNull();
        assertThat(SalesDashboardSupport.normalizeSiteCategory("D")).isNull();
        assertThat(SalesDashboardSupport.normalizeSiteCategory("AB")).isNull();
        assertThat(SalesDashboardSupport.normalizeSiteCategory("1")).isNull();
    }

    @Test
    void categoriesAggregateTheKeptSitesInOrderABCNull() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        List<CategoryRow> categories = heatmap.getCategories();
        assertThat(categories).extracting(CategoryRow::getCategory).containsExactly("A", "B", "C", null);
        assertThat(categories).extracting(CategoryRow::getKioskCount).containsExactly(2, 2, 1, 3);
        assertThat(categories).extracting(c -> c.getTotal().toPlainString())
                .containsExactly("800.00", "100.00", "270.00", "300.00");
        assertThat(categories).extracting(c -> c.getPreviousTotal().toPlainString())
                .containsExactly("200.00", "120.00", "30.00", "0.00");
        // (800 - 200) / 200; (100 - 120) / 120; (270 - 30) / 30; sin periodo anterior => 100
        assertThat(categories.get(0).getGrowthPercent()).isEqualByComparingTo("300.0");
        assertThat(categories.get(1).getGrowthPercent()).isEqualByComparingTo("-16.7");
        assertThat(categories.get(2).getGrowthPercent()).isEqualByComparingTo("800.0");
        assertThat(categories.get(3).getGrowthPercent()).isEqualByComparingTo("100");

        // Cada categoría es exactamente la suma de los sitios que la integran.
        for (CategoryRow category : categories) {
            List<SiteRow> members = heatmap.getSites().stream()
                    .filter(s -> java.util.Objects.equals(s.getCategory(), category.getCategory())).toList();
            assertThat(members).hasSize(category.getKioskCount());
            assertThat(category.getTotal()).isEqualByComparingTo(sum(members, SiteRow::getTotal));
            assertThat(category.getPreviousTotal()).isEqualByComparingTo(sum(members, SiteRow::getPreviousTotal));
        }
    }

    @Test
    void categorySharesAreOverTheGrandTotalAndAddUpToAboutOneHundred() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        // Total de todos los sitios: 800 + 100 + 270 + 300 = 1470.
        assertThat(heatmap.getCategories()).extracting(c -> c.getSharePercent().toPlainString())
                .containsExactly("54.4", "6.8", "18.4", "20.4");
        BigDecimal shares = heatmap.getCategories().stream()
                .map(CategoryRow::getSharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shares).isBetween(new BigDecimal("99.8"), new BigDecimal("100.2"));
        assertThat(sum(heatmap.getCategories().stream().map(CategoryRow::getKioskCount).map(BigDecimal::valueOf).toList()))
                .isEqualByComparingTo(BigDecimal.valueOf(heatmap.getSites().size()));
    }

    @Test
    void onlyCategoriesPresentAmongTheKeptSitesAreListed() {
        List<KioskSiteEntity> sites = List.of(
                site(1, "Solo B", null, "b"),
                site(2, "Sin clasificar", null, null),
                site(3, "Categoría C sin ventas", null, "C"));
        Map<Long, SiteSales> sales = new LinkedHashMap<>();
        sales.put(1L, siteSales(1, false, true, d(9, 12), "30.00"));
        sales.put(2L, siteSales(2, false, true, d(9, 12), "70.00"));
        sales.put(3L, siteSales(3, false, false));

        KioskHeatmapResponse heatmap = serviceOver(sites, sales).build(RANGE);

        assertThat(heatmap.getCategories()).extracting(CategoryRow::getCategory).containsExactly("B", null);
        assertThat(heatmap.getCategories()).extracting(c -> c.getSharePercent().toPlainString())
                .containsExactly("30.0", "70.0");
    }

    @Test
    void sharesAreZeroWhenAllSitesTogetherHaveNoSalesInTheCurrentPeriod() {
        List<KioskSiteEntity> sites = List.of(site(1, "Dejó de vender", null, "A"));
        Map<Long, SiteSales> sales = Map.of(1L, siteSales(1, false, true, d(9, 2), "80.00"));

        KioskHeatmapResponse heatmap = serviceOver(sites, sales).build(RANGE);

        assertThat(heatmap.getCategories()).hasSize(1);
        CategoryRow category = heatmap.getCategories().get(0);
        assertThat(category.getCategory()).isEqualTo("A");
        assertThat(category.getKioskCount()).isEqualTo(1);
        assertThat(category.getTotal().toPlainString()).isEqualTo("0.00");
        assertThat(category.getPreviousTotal().toPlainString()).isEqualTo("80.00");
        assertThat(category.getGrowthPercent()).isEqualByComparingTo("-100.0");
        assertThat(category.getSharePercent()).isEqualByComparingTo("0");
    }

    // --------------------------------------------- casos de borde de los importes

    @Test
    void negativeAndZeroDaysDoNotCountAsDaysWithSalesAndNetZeroSitesAreDropped() {
        List<KioskSiteEntity> sites = List.of(
                site(1, "Con devolución", null, "A"),
                site(2, "Neto cero", null, "A"),
                site(3, "Solo negativo", null, "A"));
        Map<Long, SiteSales> sales = new LinkedHashMap<>();
        sales.put(1L, siteSales(1, false, true, d(9, 10), "100.00", d(9, 11), "-30.00", d(9, 12), "0.00"));
        sales.put(2L, siteSales(2, false, true, d(9, 10), "50.00", d(9, 11), "-50.00"));
        sales.put(3L, siteSales(3, false, true, d(9, 10), "-20.00"));

        KioskHeatmapResponse heatmap = serviceOver(sites, sales).build(RANGE);

        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId).containsExactly(1L, 3L);
        SiteRow conDevolucion = row(heatmap, 1);
        assertThat(conDevolucion.getTotal().toPlainString()).isEqualTo("70.00");
        assertThat(conDevolucion.getDaysWithSales()).isEqualTo(1);
        assertThat(conDevolucion.getDaily().get(1).toPlainString()).isEqualTo("-30.00");
        SiteRow soloNegativo = row(heatmap, 3);
        assertThat(soloNegativo.getTotal().toPlainString()).isEqualTo("-20.00");
        assertThat(soloNegativo.getDaysWithSales()).isZero();
        assertThat(soloNegativo.getGrowthPercent()).isEqualByComparingTo("0");
    }

    @Test
    void amountsOutsideThePreviousAndCurrentWindowAreIgnored() {
        List<KioskSiteEntity> sites = List.of(site(1, "Fuera de ventana", null, "A"));
        Map<Long, SiteSales> sales = Map.of(1L, siteSales(1, false, true,
                d(8, 30), "999.00",   // un día antes del periodo anterior
                d(9, 9), "40.00",     // último día del periodo anterior
                d(9, 10), "60.00",    // primer día del periodo actual
                d(9, 19), "10.00",    // último día del periodo actual
                d(9, 20), "999.00")); // después del rango

        SiteRow row = serviceOver(sites, sales).build(RANGE).getSites().get(0);

        assertThat(row.getTotal().toPlainString()).isEqualTo("70.00");
        assertThat(row.getPreviousTotal().toPlainString()).isEqualTo("40.00");
        assertThat(row.getDaysWithSales()).isEqualTo(2);
        assertThat(row.getDaily()).hasSize(10);
        assertThat(sum(row.getDaily())).isEqualByComparingTo("70.00");
    }

    // ----------------------------------------------------- consultas y robustez

    @Test
    void aCacheMissResolvesTheFinanceSourceOnceAndNeverQueriesThePosDetail() {
        service.build(RANGE);

        verify(kioskSiteRepository, times(1)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        verifyNoMoreInteractions(kioskSiteRepository);
        // El go-live se calcula una vez y se pasa al resolver (el resolver no lo recalcula): una sola resolución
        // sobre [inicio del periodo anterior, fin del actual].
        verify(resolver, times(1)).goLiveEffective(anyCollection());
        verify(resolver, times(1)).resolve(anyCollection(), eq(PREVIOUS.from()), eq(RANGE.to()), anyMap());
        verify(resolver, never()).resolve(anyCollection(), any(), any());
        verify(posAggregateRepository, times(1)).findFirstRealSaleDateByLocation(anyCollection());
        verify(posAggregateRepository, times(1))
                .sumRealSalesByLocationAndDate(anyCollection(), eq(PREVIOUS.from()), eq(RANGE.to()));
        verify(histRepository, times(1)).findBySitesAndRange(anyCollection(), eq(PREVIOUS.from()), eq(RANGE.to()));
        // Sin detalle POS: ni cabeceras ni ítems de ventas.
        verifyNoInteractions(kioskSaleRepository, kioskSaleItemRepository);

        // 4 consultas en total: sitios, primera venta real, histórico y agregados POS.
        assertThat(queryCount()).isEqualTo(4);
    }

    @Test
    void sitesExcludedFromReportsAndLocationsWithoutASiteNeverReachTheResolverNorTheTotals() {
        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId).doesNotContain(EXCLUDED);
        assertThat(heatmap.getSites()).extracting(SiteRow::getName).doesNotContain("Excluido");
        // 777 POS del sitio excluido, 555 de su histórico y 888 de una location sin sitio: ninguno suma.
        assertThat(sum(heatmap.getSites(), SiteRow::getTotal)).isEqualByComparingTo("1470.00");

        // Solo se piden los sitios incluidos y sus locations (10, 11 y 14; nunca la 12 ni la 99).
        Set<Long> includedIds = new HashSet<>();
        includedSites.forEach(s -> includedIds.add(s.getId()));
        verify(histRepository).findBySitesAndRange(
                argThat((Collection<Long> ids) -> new HashSet<>(ids).equals(includedIds)), any(), any());
        verify(posAggregateRepository).sumRealSalesByLocationAndDate(
                argThat((Collection<Long> ids) -> new HashSet<>(ids).equals(Set.of(10L, 11L, 14L))), any(), any());
        // Y solo con el catálogo de sitios incluidos (nunca el catálogo completo).
        verify(kioskSiteRepository, never()).findAll();
        verify(kioskSiteRepository, never()).findAllByOrderBySortOrderAscNameAsc();
    }

    @Test
    void withoutIncludedSitesTheHeatmapIsEmptyAndOnlyTheSiteListIsQueried() {
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(List.of());

        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(heatmap.getDays()).hasSize(10);
        assertThat(heatmap.getSites()).isEmpty();
        assertThat(heatmap.getCategories()).isEmpty();
        assertThat(heatmap.getStartDate()).isEqualTo(RANGE.from());
        assertThat(heatmap.getPreviousEndDate()).isEqualTo(PREVIOUS.to());
        assertThat(queryCount()).isEqualTo(1);
    }

    @Test
    void historicalOnlyCatalogSkipsTheGoLiveAndPosQueries() {
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(site(MAJADAS, "Majadas historico", null, "B")));

        KioskHeatmapResponse heatmap = service.build(RANGE);

        assertThat(heatmap.getSites()).extracting(SiteRow::getSiteId).containsExactly(MAJADAS);
        // sitios + histórico: sin location no hay primera venta que buscar ni agregados POS.
        assertThat(queryCount()).isEqualTo(2);
        verifyNoInteractions(posAggregateRepository);
    }

    // ------------------------------------------------ getHeatmap: rango y caché

    @Test
    void getHeatmapDefaultsToTheCurrentMonthUpToToday() throws Exception {
        LocalDate today = SalesDashboardSupport.today();

        KioskHeatmapResponse heatmap = service.getHeatmap(null, null, false);

        assertThat(heatmap.getStartDate()).isEqualTo(today.withDayOfMonth(1));
        assertThat(heatmap.getEndDate()).isEqualTo(today);
        assertThat(heatmap.getDays()).hasSize(today.getDayOfMonth());
        assertThat(heatmap.getDays().get(heatmap.getDays().size() - 1)).isEqualTo(today);
    }

    @Test
    void invertedRangeIsRejectedWithTheDashboardMessageBeforeAnyQuery() {
        assertThatThrownBy(() -> service.getHeatmap(d(9, 19), d(9, 10), false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("La fecha inicial no puede ser posterior a la fecha final.");

        verifyNoInteractions(kioskSiteRepository, posAggregateRepository, histRepository);
    }

    @Test
    void rangesOverFourHundredDaysAreRejectedBeforeAnyQuery() {
        LocalDate to = d(9, 30);

        // 401 días (el 30 sep y los 400 anteriores).
        assertThatThrownBy(() -> service.getHeatmap(to.minusDays(400), to, false))
                .isInstanceOf(BusinessException.class).hasMessage(MAX_DAYS_MESSAGE);
        assertThatThrownBy(() -> service.getHeatmap(to.minusDays(400), to, true))
                .isInstanceOf(BusinessException.class).hasMessage(MAX_DAYS_MESSAGE);
        assertThatThrownBy(() -> service.getHeatmap(d(1, 1).minusYears(5), to, false))
                .isInstanceOf(BusinessException.class).hasMessage(MAX_DAYS_MESSAGE);

        verifyNoInteractions(kioskSiteRepository, posAggregateRepository, histRepository);
    }

    @Test
    void exactlyFourHundredDaysIsAccepted() throws Exception {
        LocalDate to = d(9, 30);

        KioskHeatmapResponse heatmap = service.getHeatmap(to.minusDays(399), to, false);

        assertThat(heatmap.getDays()).hasSize(400);
        assertThat(heatmap.getDays().get(0)).isEqualTo(to.minusDays(399));
        assertThat(heatmap.getPreviousStartDate()).isEqualTo(to.minusDays(799));
        assertThat(heatmap.getPreviousEndDate()).isEqualTo(to.minusDays(400));
        // La ventana cargada es la del periodo anterior más la del actual: 800 días en una sola resolución.
        verify(resolver, times(1)).resolve(anyCollection(), eq(to.minusDays(799)), eq(to), anyMap());
        for (SiteRow site : heatmap.getSites()) {
            assertThat(site.getDaily()).hasSize(400);
        }
    }

    @Test
    void cacheKeyIsKioskHeatmapWithTheDatesAndNoKioskFilter() {
        assertThat(SalesDashboardCache.key("KIOSK_HEATMAP", d(9, 10), d(9, 19), null, null))
                .isEqualTo("KIOSK_HEATMAP|2026-09-10|2026-09-19||");
        // No comparte entrada con el dashboard de kioscos del mismo rango.
        assertThat(SalesDashboardCache.key("KIOSK_HEATMAP", d(9, 10), d(9, 19), null, null))
                .isNotEqualTo(SalesDashboardCache.key("KIOSKO", d(9, 10), d(9, 19), null, null));
    }

    @Test
    void getHeatmapCachesPerRangeUnderTheHeatmapKeyAndRefreshRecomputes() throws Exception {
        String key = "KIOSK_HEATMAP|2026-09-10|2026-09-19||";

        KioskHeatmapResponse first = service.getHeatmap(RANGE.from(), RANGE.to(), false);
        KioskHeatmapResponse second = service.getHeatmap(RANGE.from(), RANGE.to(), false);

        assertThat(second).isSameAs(first);
        assertThat(cached(key)).isSameAs(first);
        verify(kioskSiteRepository, times(1)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // Otro rango es otra entrada.
        KioskHeatmapResponse other = service.getHeatmap(d(9, 11), RANGE.to(), false);
        assertThat(other).isNotSameAs(first);
        assertThat(other.getDays()).hasSize(9);
        assertThat(cached("KIOSK_HEATMAP|2026-09-11|2026-09-19||")).isSameAs(other);
        verify(kioskSiteRepository, times(2)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // refresh=true la omite: recalcula (misma cifra, otro objeto) y deja el nuevo valor en la caché.
        KioskHeatmapResponse refreshed = service.getHeatmap(RANGE.from(), RANGE.to(), true);
        assertThat(refreshed).isNotSameAs(first).isEqualTo(first);
        verify(kioskSiteRepository, times(3)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        assertThat(cached(key)).isSameAs(refreshed);
        assertThat(service.getHeatmap(RANGE.from(), RANGE.to(), false)).isSameAs(refreshed);
        verify(kioskSiteRepository, times(3)).findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        // La entrada del otro rango no se tocó.
        assertThat(cached("KIOSK_HEATMAP|2026-09-11|2026-09-19||")).isSameAs(other);
    }

    @Test
    void rejectedRangesAreNotCached() throws Exception {
        assertThatThrownBy(() -> service.getHeatmap(d(9, 19), d(9, 10), false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getHeatmap(d(1, 1).minusYears(3), d(9, 10), false))
                .isInstanceOf(BusinessException.class);

        assertThat(cached("KIOSK_HEATMAP|2026-09-19|2026-09-10||")).isNull();
        // Y un rango válido posterior sigue calculándose con normalidad.
        assertThat(service.getHeatmap(RANGE.from(), RANGE.to(), false).getSites()).isNotEmpty();
    }

    // ------------------------------------------------------------------- JSON

    @Test
    void jsonUsesTheContractFieldNamesAndSerializesNullsAndNumbers() throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(service.build(RANGE)));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "startDate", "endDate", "previousStartDate", "previousEndDate", "days", "sites", "categories");
        assertThat(json.get("startDate").asText()).isEqualTo("2026-09-10");
        assertThat(json.get("endDate").asText()).isEqualTo("2026-09-19");
        assertThat(json.get("previousStartDate").asText()).isEqualTo("2026-08-31");
        assertThat(json.get("previousEndDate").asText()).isEqualTo("2026-09-09");
        assertThat(json.get("days").size()).isEqualTo(10);
        assertThat(json.get("days").get(0).asText()).isEqualTo("2026-09-10");
        assertThat(json.get("days").get(9).asText()).isEqualTo("2026-09-19");

        assertThat(json.get("sites").size()).isEqualTo(8);
        for (JsonNode site : json.get("sites")) {
            assertThat(fieldNames(site)).containsExactlyInAnyOrder("siteId", "name", "category", "locationId",
                    "source", "total", "previousTotal", "growthPercent", "daysWithSales", "daily");
            assertThat(site.get("daily").size()).isEqualTo(json.get("days").size());
            assertThat(site.get("daily").get(0).isNumber()).isTrue();
            assertThat(site.get("total").isNumber()).isTrue();
            assertThat(site.get("previousTotal").isNumber()).isTrue();
            assertThat(site.get("growthPercent").isNumber()).isTrue();
            assertThat(site.get("daysWithSales").isInt()).isTrue();
        }
        JsonNode majadas = siteJson(json, MAJADAS);
        assertThat(majadas.get("name").asText()).isEqualTo("Majadas historico");
        assertThat(majadas.get("category").asText()).isEqualTo("B");
        assertThat(majadas.get("source").asText()).isEqualTo("HIST");
        assertThat(majadas.has("locationId")).isTrue();
        assertThat(majadas.get("locationId").isNull()).isTrue();
        assertThat(majadas.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(majadas.get("previousTotal").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(majadas.get("growthPercent").decimalValue()).isEqualByComparingTo("150.0");
        assertThat(majadas.get("daysWithSales").asInt()).isEqualTo(2);
        JsonNode unclassified = siteJson(json, TERRAZA);
        assertThat(unclassified.has("category")).isTrue();
        assertThat(unclassified.get("category").isNull()).isTrue();
        assertThat(siteJson(json, MIRAFLORES).get("locationId").asLong()).isEqualTo(10L);

        assertThat(json.get("categories").size()).isEqualTo(4);
        for (JsonNode category : json.get("categories")) {
            assertThat(fieldNames(category)).containsExactlyInAnyOrder(
                    "category", "kioskCount", "total", "previousTotal", "growthPercent", "sharePercent");
            assertThat(category.get("kioskCount").isInt()).isTrue();
            assertThat(category.get("sharePercent").isNumber()).isTrue();
        }
        assertThat(json.get("categories").get(0).get("category").asText()).isEqualTo("A");
        JsonNode lastCategory = json.get("categories").get(3);
        assertThat(lastCategory.has("category")).isTrue();
        assertThat(lastCategory.get("category").isNull()).isTrue();
        assertThat(lastCategory.get("sharePercent").decimalValue()).isEqualByComparingTo("20.4");
    }

    // -------------------------------------------------------------- utilidades

    private KioskHeatmapService serviceOver(List<KioskSiteEntity> sites, Map<Long, SiteSales> sales) {
        KioskSalesSourceResolver financeSource = mock(KioskSalesSourceResolver.class);
        when(kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()).thenReturn(sites);
        when(financeSource.goLiveEffective(anyCollection())).thenReturn(Map.of());
        when(financeSource.resolve(anyCollection(), any(), any(), anyMap())).thenReturn(sales);
        return new KioskHeatmapService(kioskSiteRepository, financeSource, cache);
    }

    /** Ventas resueltas a mano: pares fecha / monto. */
    private static SiteSales siteSales(long siteId, boolean hasPos, boolean hasHist, Object... dateAndAmount) {
        NavigableMap<LocalDate, BigDecimal> daily = new TreeMap<>();
        for (int i = 0; i < dateAndAmount.length; i += 2) {
            daily.put((LocalDate) dateAndAmount[i], new BigDecimal((String) dateAndAmount[i + 1]));
        }
        return new SiteSales(siteId, null, daily, hasPos, hasHist);
    }

    private static SiteRow row(KioskHeatmapResponse heatmap, long siteId) {
        return heatmap.getSites().stream()
                .filter(site -> site.getSiteId() == siteId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("El sitio " + siteId + " no está en el mapa de calor"));
    }

    private static CategoryRow category(KioskHeatmapResponse heatmap, String category) {
        return heatmap.getCategories().stream()
                .filter(row -> java.util.Objects.equals(row.getCategory(), category))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La categoría " + category + " no está en el mapa de calor"));
    }

    private static JsonNode siteJson(JsonNode json, long siteId) {
        for (JsonNode site : json.get("sites")) {
            if (site.get("siteId").asLong() == siteId) {
                return site;
            }
        }
        throw new AssertionError("El sitio " + siteId + " no está en el JSON");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> plain(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).toList();
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Object cached(String key) {
        org.springframework.cache.Cache.ValueWrapper wrapper =
                cacheManager.getCache(CacheConfig.SALES_DASHBOARD_CACHE).get(key);
        return wrapper != null ? wrapper.get() : null;
    }

    /** Número de consultas a base de datos que hizo el servicio (repositorios que consulta, uno por interacción). */
    private int queryCount() {
        return Mockito.mockingDetails(kioskSiteRepository).getInvocations().size()
                + Mockito.mockingDetails(posAggregateRepository).getInvocations().size()
                + Mockito.mockingDetails(histRepository).getInvocations().size()
                + Mockito.mockingDetails(kioskSaleRepository).getInvocations().size()
                + Mockito.mockingDetails(kioskSaleItemRepository).getInvocations().size();
    }
}
