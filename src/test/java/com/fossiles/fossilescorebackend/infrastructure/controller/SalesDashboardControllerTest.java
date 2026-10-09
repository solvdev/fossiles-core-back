package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.KioskOption;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.OnlineSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.OpvShipmentCatalogService;
import com.fossiles.fossilescorebackend.application.service.SalesConsolidatedService;
import com.fossiles.fossilescorebackend.application.service.SalesUnifiedService;
import com.fossiles.fossilescorebackend.application.service.VendorSalesDashboardService;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parámetros y forma del JSON de {@code GET /api/sales/dashboard/kiosks} (docs/SALES-DASHBOARD-CONTRACT.md, addendum 2).
 * La autenticación la aplica SecurityConfig (anyRequest().authenticated()) y no entra en esta prueba.
 */
class SalesDashboardControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 10);
    private static final LocalDate TO = LocalDate.of(2026, 9, 19);

    private KioskSalesDashboardService kioskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        kioskService = mock(KioskSalesDashboardService.class);
        // Igual que Spring Boot: fechas ISO (no arreglos) y propiedades nulas incluidas.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mvc = MockMvcBuilders.standaloneSetup(new SalesDashboardController(
                        mock(SalesConsolidatedService.class), kioskService, mock(OnlineSalesDashboardService.class),
                        mock(VendorSalesDashboardService.class), mock(SalesUnifiedService.class),
                        mock(OpvShipmentCatalogService.class)))
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
                .breakdowns(Map.of("byKiosk", List.of(BreakdownRow.builder()
                        .key("2").label("Majadas").count(0).amount(new BigDecimal("100.00"))
                        .sharePercent(new BigDecimal("13.0")).build())))
                .kioskOptions(List.of(
                        KioskOption.builder().siteId(2L).kioskId(null).kioskCode("").kioskName("Majadas").build(),
                        KioskOption.builder().siteId(1L).kioskId(10L).kioskCode("K10").kioskName("Miraflores").build()))
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
}
