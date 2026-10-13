package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.CategoryRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.SiteRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.KioskOption;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskHeatmapService;
import com.fossiles.fossilescorebackend.application.service.KioskSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver;
import com.fossiles.fossilescorebackend.application.service.OnlineSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.OpvShipmentCatalogService;
import com.fossiles.fossilescorebackend.application.service.SalesConsolidatedService;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardCache;
import com.fossiles.fossilescorebackend.application.service.SalesUnifiedService;
import com.fossiles.fossilescorebackend.application.service.VendorSalesDashboardService;
import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parámetros y forma del JSON de {@code GET /api/sales/dashboard/kiosks} y de
 * {@code GET /api/sales/dashboard/kiosks/heatmap} (docs/SALES-DASHBOARD-CONTRACT.md, addendums 2 y 3).
 * La autenticación la aplica SecurityConfig (anyRequest().authenticated()) y no entra en esta prueba.
 */
class SalesDashboardControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 10);
    private static final LocalDate TO = LocalDate.of(2026, 9, 19);
    private static final String HEATMAP_PATH = "/api/sales/dashboard/kiosks/heatmap";
    private static final String MAX_DAYS_MESSAGE = "El mapa de calor admite un máximo de 400 días.";

    private KioskSalesDashboardService kioskService;
    private KioskHeatmapService heatmapService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        kioskService = mock(KioskSalesDashboardService.class);
        heatmapService = mock(KioskHeatmapService.class);
        mvc = mvcWith(heatmapService);
    }

    private MockMvc mvcWith(KioskHeatmapService heatmap) {
        // Igual que Spring Boot: fechas ISO (no arreglos) y propiedades nulas incluidas.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        return MockMvcBuilders.standaloneSetup(new SalesDashboardController(
                        mock(SalesConsolidatedService.class), kioskService, heatmap,
                        mock(OnlineSalesDashboardService.class), mock(VendorSalesDashboardService.class),
                        mock(SalesUnifiedService.class), mock(OpvShipmentCatalogService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private static SalesSourceDetailResponse kioskResponse() {
        return SalesSourceDetailResponse.builder()
                .channel("KIOSKO").label("Kioskos")
                .startDate(FROM).endDate(TO).previousStartDate(FROM.minusDays(10)).previousEndDate(FROM.minusDays(1))
                .kpis(SourceKpis.builder()
                        .totalAmount(new BigDecimal("770.00")).productAmount(new BigDecimal("550.00"))
                        .packagingAmount(new BigDecimal("50.00")).shippingAmount(new BigDecimal("0.00"))
                        .historicalAmount(new BigDecimal("170.00")).previousTotalAmount(new BigDecimal("270.00"))
                        .growthPercent(new BigDecimal("185.2")).dailyAmount(new BigDecimal("0.00"))
                        .salesCount(4).unitsFinished(new BigDecimal("7.00")).avgTicket(new BigDecimal("150.00")).build())
                .breakdowns(Map.of("byKiosk", List.of(
                        BreakdownRow.builder()
                                .key("2").label("Majadas").count(0).amount(new BigDecimal("100.00"))
                                .sharePercent(new BigDecimal("13.0")).category("B").build(),
                        BreakdownRow.builder()
                                .key("1").label("Miraflores").count(2).amount(new BigDecimal("400.00"))
                                .sharePercent(new BigDecimal("51.9")).category(null).build())))
                .kioskOptions(List.of(
                        KioskOption.builder().siteId(2L).kioskId(null).kioskCode("").kioskName("Majadas")
                                .category("B").build(),
                        KioskOption.builder().siteId(1L).kioskId(10L).kioskCode("K10").kioskName("Miraflores")
                                .category(null).build()))
                .build();
    }

    private static KioskHeatmapResponse heatmapResponse() {
        BigDecimal zero = new BigDecimal("0.00");
        return KioskHeatmapResponse.builder()
                .startDate(FROM).endDate(FROM.plusDays(2))
                .previousStartDate(FROM.minusDays(3)).previousEndDate(FROM.minusDays(1))
                .days(List.of(FROM, FROM.plusDays(1), FROM.plusDays(2)))
                .sites(List.of(
                        SiteRow.builder().siteId(1L).name("Miraflores").category("A").locationId(10L).source("POS")
                                .total(new BigDecimal("400.00")).previousTotal(new BigDecimal("200.00"))
                                .growthPercent(new BigDecimal("100.0")).daysWithSales(2)
                                .daily(List.of(new BigDecimal("300.00"), zero, new BigDecimal("100.00"))).build(),
                        SiteRow.builder().siteId(2L).name("Majadas historico").category(null).locationId(null)
                                .source("HIST")
                                .total(new BigDecimal("100.00")).previousTotal(new BigDecimal("40.00"))
                                .growthPercent(new BigDecimal("150.0")).daysWithSales(1)
                                .daily(List.of(zero, new BigDecimal("100.00"), zero)).build()))
                .categories(List.of(
                        CategoryRow.builder().category("A").kioskCount(1).total(new BigDecimal("400.00"))
                                .previousTotal(new BigDecimal("200.00")).growthPercent(new BigDecimal("100.0"))
                                .sharePercent(new BigDecimal("80.0")).build(),
                        CategoryRow.builder().category(null).kioskCount(1).total(new BigDecimal("100.00"))
                                .previousTotal(new BigDecimal("40.00")).growthPercent(new BigDecimal("150.0"))
                                .sharePercent(new BigDecimal("20.0")).build()))
                .build();
    }

    @Test
    void kiosksPassesSiteIdToTheServiceAndExposesTheNewFields() throws Exception {
        when(kioskService.getDashboard(any(), any(), any(), any(), anyBoolean())).thenReturn(kioskResponse());

        mvc.perform(get("/api/sales/dashboard/kiosks")
                        .param("startDate", "2026-09-10").param("endDate", "2026-09-19").param("siteId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("KIOSKO"))
                .andExpect(jsonPath("$.kpis.totalAmount").value(770.00))
                .andExpect(jsonPath("$.kpis.historicalAmount").value(170.00))
                .andExpect(jsonPath("$.kpis.avgTicket").value(150.00))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].key").value("2"))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].count").value(0))
                .andExpect(jsonPath("$.kioskOptions[0].siteId").value(2))
                .andExpect(jsonPath("$.kioskOptions[0].kioskId").value(nullValue()))
                .andExpect(jsonPath("$.kioskOptions[0].kioskCode").value(""))
                .andExpect(jsonPath("$.kioskOptions[0].kioskName").value("Majadas"))
                .andExpect(jsonPath("$.kioskOptions[1].siteId").value(1))
                .andExpect(jsonPath("$.kioskOptions[1].kioskId").value(10))
                .andExpect(jsonPath("$.kioskOptions[1].kioskCode").value("K10"));

        verify(kioskService).getDashboard(FROM, TO, 7L, null, false);
    }

    @Test
    void kiosksExposesTheCategoryInKioskOptionsAndByKioskRowsAndKeepsEveryExistingField() throws Exception {
        when(kioskService.getDashboard(any(), any(), any(), any(), anyBoolean())).thenReturn(kioskResponse());

        mvc.perform(get("/api/sales/dashboard/kiosks").param("startDate", "2026-09-10").param("endDate", "2026-09-19"))
                .andExpect(status().isOk())
                // kioskOptions: el campo nuevo convive con siteId, kioskId, kioskCode y kioskName.
                .andExpect(jsonPath("$.kioskOptions[0]", hasKey("siteId")))
                .andExpect(jsonPath("$.kioskOptions[0]", hasKey("kioskId")))
                .andExpect(jsonPath("$.kioskOptions[0]", hasKey("kioskCode")))
                .andExpect(jsonPath("$.kioskOptions[0]", hasKey("kioskName")))
                .andExpect(jsonPath("$.kioskOptions[0].category").value("B"))
                .andExpect(jsonPath("$.kioskOptions[1]", hasKey("category")))
                .andExpect(jsonPath("$.kioskOptions[1].category").value(nullValue()))
                // byKiosk: key, label, count, amount y sharePercent siguen igual; category es nuevo (null = sin clasificar).
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].key").value("2"))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].label").value("Majadas"))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].count").value(0))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].amount").value(100.00))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].sharePercent").value(13.0))
                .andExpect(jsonPath("$.breakdowns.byKiosk[0].category").value("B"))
                .andExpect(jsonPath("$.breakdowns.byKiosk[1]", hasKey("category")))
                .andExpect(jsonPath("$.breakdowns.byKiosk[1].category").value(nullValue()));
    }

    @Test
    void kiosksStillAcceptsTheLegacyKioskLocationIdAndRefresh() throws Exception {
        when(kioskService.getDashboard(any(), any(), any(), any(), anyBoolean())).thenReturn(kioskResponse());

        mvc.perform(get("/api/sales/dashboard/kiosks")
                        .param("startDate", "2026-09-10").param("endDate", "2026-09-19")
                        .param("kioskLocationId", "4").param("refresh", "true"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sales/dashboard/kiosks")
                        .param("startDate", "2026-09-10").param("endDate", "2026-09-19")
                        .param("siteId", "7").param("kioskLocationId", "4"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sales/dashboard/kiosks")).andExpect(status().isOk());

        verify(kioskService).getDashboard(FROM, TO, null, 4L, true);
        verify(kioskService).getDashboard(FROM, TO, 7L, 4L, false);
        verify(kioskService).getDashboard(null, null, null, null, false);
    }

    @Test
    void unknownSiteBecomesBadRequestWithTheSpanishMessage() throws Exception {
        when(kioskService.getDashboard(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BusinessException("El kiosko seleccionado no existe o no está incluido en los reportes."));

        mvc.perform(get("/api/sales/dashboard/kiosks").param("siteId", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El kiosko seleccionado no existe o no está incluido en los reportes."));
    }

    // ------------------------------------------------- mapa de calor (addendum 3)

    @Test
    void heatmapExposesTheContractShape() throws Exception {
        when(heatmapService.getHeatmap(any(), any(), anyBoolean())).thenReturn(heatmapResponse());

        mvc.perform(get(HEATMAP_PATH).param("startDate", "2026-09-10").param("endDate", "2026-09-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-09-10"))
                .andExpect(jsonPath("$.endDate").value("2026-09-12"))
                .andExpect(jsonPath("$.previousStartDate").value("2026-09-07"))
                .andExpect(jsonPath("$.previousEndDate").value("2026-09-09"))
                // days: fechas ISO, una por día del rango.
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0]").value("2026-09-10"))
                .andExpect(jsonPath("$.days[2]").value("2026-09-12"))
                // sites[0]: sitio con POS, categoría y location.
                .andExpect(jsonPath("$.sites.length()").value(2))
                .andExpect(jsonPath("$.sites[0].siteId").value(1))
                .andExpect(jsonPath("$.sites[0].name").value("Miraflores"))
                .andExpect(jsonPath("$.sites[0].category").value("A"))
                .andExpect(jsonPath("$.sites[0].locationId").value(10))
                .andExpect(jsonPath("$.sites[0].source").value("POS"))
                .andExpect(jsonPath("$.sites[0].total").value(400.00))
                .andExpect(jsonPath("$.sites[0].previousTotal").value(200.00))
                .andExpect(jsonPath("$.sites[0].growthPercent").value(100.0))
                .andExpect(jsonPath("$.sites[0].daysWithSales").value(2))
                .andExpect(jsonPath("$.sites[0].daily.length()").value(3))
                .andExpect(jsonPath("$.sites[0].daily[0]").value(300.00))
                .andExpect(jsonPath("$.sites[0].daily[1]").value(0.0))
                .andExpect(jsonPath("$.sites[0].daily[2]").value(100.00))
                // sites[1]: sitio histórico sin location y sin categoría: los nulos viajan como null (no se omiten).
                .andExpect(jsonPath("$.sites[1]", hasKey("category")))
                .andExpect(jsonPath("$.sites[1].category").value(nullValue()))
                .andExpect(jsonPath("$.sites[1]", hasKey("locationId")))
                .andExpect(jsonPath("$.sites[1].locationId").value(nullValue()))
                .andExpect(jsonPath("$.sites[1].source").value("HIST"))
                // categories: una fila por categoría, la última sin categoría.
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].category").value("A"))
                .andExpect(jsonPath("$.categories[0].kioskCount").value(1))
                .andExpect(jsonPath("$.categories[0].total").value(400.00))
                .andExpect(jsonPath("$.categories[0].previousTotal").value(200.00))
                .andExpect(jsonPath("$.categories[0].growthPercent").value(100.0))
                .andExpect(jsonPath("$.categories[0].sharePercent").value(80.0))
                .andExpect(jsonPath("$.categories[1]", hasKey("category")))
                .andExpect(jsonPath("$.categories[1].category").value(nullValue()))
                .andExpect(jsonPath("$.categories[1].sharePercent").value(20.0));
    }

    @Test
    void heatmapPassesTheDatesAndRefreshToTheService() throws Exception {
        when(heatmapService.getHeatmap(any(), any(), anyBoolean())).thenReturn(heatmapResponse());

        mvc.perform(get(HEATMAP_PATH).param("startDate", "2026-09-10").param("endDate", "2026-09-19"))
                .andExpect(status().isOk());
        mvc.perform(get(HEATMAP_PATH)
                        .param("startDate", "2026-09-10").param("endDate", "2026-09-19").param("refresh", "true"))
                .andExpect(status().isOk());
        mvc.perform(get(HEATMAP_PATH)).andExpect(status().isOk());

        verify(heatmapService).getHeatmap(FROM, TO, false);
        verify(heatmapService).getHeatmap(FROM, TO, true);
        // Sin parámetros: el servicio aplica los valores por defecto (1 del mes actual hasta hoy).
        verify(heatmapService).getHeatmap(null, null, false);
    }

    @Test
    void heatmapHasNoKioskFilterParameters() throws Exception {
        when(heatmapService.getHeatmap(any(), any(), anyBoolean())).thenReturn(heatmapResponse());

        // siteId / kioskLocationId se ignoran: el mapa de calor siempre compara todos los sitios incluidos.
        mvc.perform(get(HEATMAP_PATH).param("siteId", "7").param("kioskLocationId", "4"))
                .andExpect(status().isOk());

        verify(heatmapService).getHeatmap(null, null, false);
    }

    @Test
    void heatmapRouteIsDistinctFromTheKiosksRoute() throws Exception {
        when(kioskService.getDashboard(any(), any(), any(), any(), anyBoolean())).thenReturn(kioskResponse());
        when(heatmapService.getHeatmap(any(), any(), anyBoolean())).thenReturn(heatmapResponse());

        // La ruta del mapa de calor no cae en el dashboard de kioscos...
        mvc.perform(get(HEATMAP_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sites").isArray())
                .andExpect(jsonPath("$.channel").doesNotExist());
        verifyNoInteractions(kioskService);

        // ...y la de kioscos no cae en el mapa de calor.
        mvc.perform(get("/api/sales/dashboard/kiosks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("KIOSKO"))
                .andExpect(jsonPath("$.sites").doesNotExist());
        verify(kioskService, times(1)).getDashboard(null, null, null, null, false);
        verify(heatmapService, times(1)).getHeatmap(null, null, false);
    }

    @Test
    void heatmapBusinessErrorsBecomeBadRequestWithTheServiceMessage() throws Exception {
        when(heatmapService.getHeatmap(any(), any(), anyBoolean())).thenThrow(new BusinessException(MAX_DAYS_MESSAGE));

        mvc.perform(get(HEATMAP_PATH).param("startDate", "2024-01-01").param("endDate", "2026-09-19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MAX_DAYS_MESSAGE));
    }

    @Test
    void heatmapOverFourHundredDaysIsBadRequestWithTheExactMessageFromTheRealService() throws Exception {
        MockMvc realMvc = mvcWith(realHeatmapService());
        LocalDate end = LocalDate.of(2026, 9, 30);

        // 401 días: el servicio real lo rechaza antes de consultar nada.
        realMvc.perform(get(HEATMAP_PATH)
                        .param("startDate", end.minusDays(400).toString()).param("endDate", end.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MAX_DAYS_MESSAGE));
        // El tope es inclusivo: 400 días exactos se atienden (sin sitios: respuesta vacía pero completa).
        realMvc.perform(get(HEATMAP_PATH)
                        .param("startDate", end.minusDays(399).toString()).param("endDate", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(400))
                .andExpect(jsonPath("$.days[0]").value(end.minusDays(399).toString()))
                .andExpect(jsonPath("$.days[399]").value("2026-09-30"))
                .andExpect(jsonPath("$.sites.length()").value(0))
                .andExpect(jsonPath("$.categories.length()").value(0));
        // Rango invertido: el mensaje de siempre del dashboard.
        realMvc.perform(get(HEATMAP_PATH).param("startDate", "2026-09-19").param("endDate", "2026-09-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha inicial no puede ser posterior a la fecha final."));
    }

    /** Servicio real (sin sitios incluidos) para ejercer las validaciones de rango de punta a punta. */
    private static KioskHeatmapService realHeatmapService() {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        return new KioskHeatmapService(
                mock(KioskSiteRepository.class),
                mock(KioskSalesSourceResolver.class),
                new SalesDashboardCache(manager, mock(PlatformTransactionManager.class)));
    }
}
