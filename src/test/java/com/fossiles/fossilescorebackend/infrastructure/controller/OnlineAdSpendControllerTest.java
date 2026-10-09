package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.OnlineAdSpendService;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rutas, códigos de estado y forma del JSON del contrato (docs/SALES-DASHBOARD-CONTRACT.md, addendum de publicidad).
 * La autenticación la aplica SecurityConfig (anyRequest().authenticated()) y no entra en esta prueba.
 */
class OnlineAdSpendControllerTest {

    private OnlineAdSpendService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OnlineAdSpendService.class);
        // Igual que Spring Boot: fechas ISO (no arreglos) y propiedades nulas incluidas.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mvc = MockMvcBuilders.standaloneSetup(new OnlineAdSpendController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void putUpsertsTheDayFromThePathAndReturnsTheEntry() throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 3);
        when(service.upsert(eq(day), any(), any())).thenReturn(OnlineAdSpendEntryResponse.builder()
                .date(day).amount(new BigDecimal("1500.00")).notes("Meta")
                .updatedAt(LocalDateTime.of(2026, 9, 4, 8, 30)).updatedBy("Eduardo Ramirez").build());

        mvc.perform(put("/api/sales/online/ad-spend/2026-09-03")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1500.00,\"notes\":\"Meta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-03"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.notes").value("Meta"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-04T08:30:00"))
                .andExpect(jsonPath("$.updatedBy").value("Eduardo Ramirez"));

        verify(service).upsert(day, new BigDecimal("1500.00"), "Meta");
    }

    @Test
    void businessErrorsBecomeBadRequestWithTheSpanishMessage() throws Exception {
        when(service.upsert(any(), any(), any())).thenThrow(new BusinessException("El monto de la inversión no puede ser negativo."));

        mvc.perform(put("/api/sales/online/ad-spend/2026-09-03")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El monto de la inversión no puede ser negativo."));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete("/api/sales/online/ad-spend/2026-09-03"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(LocalDate.of(2026, 9, 3));
    }

    @Test
    void getListPassesTheRangeAndReturnsAnArray() throws Exception {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        when(service.list(from, to)).thenReturn(List.of(
                OnlineAdSpendEntryResponse.builder().date(from).amount(new BigDecimal("10.00")).build()));

        mvc.perform(get("/api/sales/online/ad-spend").param("startDate", "2026-09-01").param("endDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$[0].notes").value((Object) null))
                .andExpect(jsonPath("$[0].updatedBy").value((Object) null));
    }

    @Test
    void bulkParsesNullAmountAsDeleteAndReturnsCounts() throws Exception {
        when(service.bulk(any())).thenReturn(OnlineAdSpendBulkResponse.builder().saved(1).deleted(1).build());

        mvc.perform(post("/api/sales/online/ad-spend/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"date\":\"2026-09-03\",\"amount\":1500.00,\"notes\":null},"
                                + "{\"date\":\"2026-09-04\",\"amount\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1))
                .andExpect(jsonPath("$.deleted").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OnlineAdSpendBulkRequest.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).bulk(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(captor.getValue().get(0).getAmount()).isEqualByComparingTo("1500.00");
        assertThat(captor.getValue().get(1).getAmount()).isNull();
    }

    @Test
    void reportIsNotShadowedByThePathVariableRouteAndKeepsNullFields() throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 3);
        when(service.report(day, day)).thenReturn(OnlineAdSpendReportResponse.builder()
                .startDate(day).endDate(day)
                .totals(OnlineAdSpendReportResponse.Totals.builder()
                        .salesAmount(new BigDecimal("100.00")).ordersCount(2).comparableSales(new BigDecimal("0.00"))
                        .adSpend(new BigDecimal("0.00")).netResult(new BigDecimal("0.00")).roas(null)
                        .daysWithSpend(0).daysNoSpend(1).daysWin(0).daysLoss(0).daysEven(0).build())
                .days(List.of(OnlineAdSpendReportResponse.Day.builder()
                        .date(day).salesAmount(new BigDecimal("100.00")).ordersCount(2).status("NO_SPEND").build()))
                .build());

        mvc.perform(get("/api/sales/online/ad-spend/report").param("startDate", "2026-09-03").param("endDate", "2026-09-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-09-03"))
                .andExpect(jsonPath("$.totals.salesAmount").value(100.00))
                .andExpect(jsonPath("$.totals.roas").value((Object) null))
                .andExpect(jsonPath("$.totals.daysNoSpend").value(1))
                .andExpect(jsonPath("$.days[0].status").value("NO_SPEND"))
                .andExpect(jsonPath("$.days[0].adSpend").value((Object) null))
                .andExpect(jsonPath("$.days[0].netResult").value((Object) null))
                .andExpect(jsonPath("$.days[0].roas").value((Object) null));
    }
}
