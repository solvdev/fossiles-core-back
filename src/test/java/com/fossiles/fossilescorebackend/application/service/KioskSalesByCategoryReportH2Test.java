package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Row;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipReturnedItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipReturnedItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/reports/kiosk-sales-by-category on H2. One class, every case from the brief.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kiosk_sales_by_category;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sentry.enabled=false",
        "sentry.dsn=",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class KioskSalesByCategoryReportH2Test {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private ProductCategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private KioskSaleRepository saleRepository;
    @Autowired
    private KioskExchangeSlipRepository slipRepository;
    @Autowired
    private KioskExchangeSlipReturnedItemRepository returnedItemRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private UserEntity admin;
    private LocationEntity kiosk;

    @BeforeEach
    void adminUser() {
        admin = userWithRole("ADMIN");
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());
        kiosk = kiosk("K" + SEQ.incrementAndGet(), false);
    }

    @Test
    void headerDiscountRemainderAndPackagingStayOutOfTheBase() throws Exception {
        LocalDate day = LocalDate.of(2026, 6, 1);
        ProductCategoryEntity cintos = category("Cintos");
        ProductCategoryEntity bolsas = category("Bolsas");
        ProductEntity a = product("CIN-A", cintos);
        ProductEntity b = product("CIN-B", cintos);
        ProductEntity c = product("CIN-C", cintos);
        ProductEntity pack = product("SUM-BOLSA", bolsas);
        ProductEntity d = product("CIN-D", cintos);
        ProductEntity e = product("CIN-E", cintos);

        sale(kiosk, day, day.atTime(10, 0), "COMPLETED", false, null, money("1.00"), money("29.00"),
                line(a, "CIN-A", "1", "10.00", "10.00"),
                line(b, "CIN-B", "1", "10.00", "10.00"),
                line(c, "CIN-C", "1", "10.00", "10.00"));
        sale(kiosk, day, day.atTime(11, 0), "COMPLETED", false, null, money("0.01"), money("24.99"),
                line(pack, "SUM-BOLSA", "1", "5.00", "5.00"),
                line(d, "CIN-D", "1", "10.00", "10.00"),
                line(e, "CIN-E", "1", "10.00", "10.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(2);
        assertMoney(row(report, "Cintos").getTotalAmount(), "48.99");
        assertMoney(row(report, "Bolsas").getTotalAmount(), "5.00");
        assertMoney(sumAmounts(report), "53.99");
        assertMoney(report.getTotals().getTotalAmount(), "53.99");
    }

    @Test
    void returnUsesPaidPriceNotListPriceAndRejectedDoesNotCount() throws Exception {
        ProductCategoryEntity cat = category("Cuero");
        ProductEntity product = product("CUO-1", cat);
        LocalDate october = LocalDate.of(2026, 10, 15);
        LocalDate november = LocalDate.of(2026, 11, 10);
        KioskSaleEntity sale = sale(kiosk, october, october.atTime(12, 0), "COMPLETED", false, null,
                money("20.00"), money("80.00"),
                line(product, "CUO-1", "1", "100.00", "100.00"));
        KioskSaleItemEntity item = sale.getItems().get(0);
        headerReturn(kiosk, sale, item, product, "1", "100.00", "PENDING_REINTEGRO", november.atTime(9, 0), null);

        ProductEntity other = product("CUO-2", cat);
        KioskSaleEntity rejectedSale = sale(kiosk, october, october.atTime(13, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("50.00"),
                line(other, "CUO-2", "1", "50.00", "50.00"));
        headerReturn(kiosk, rejectedSale, rejectedSale.getItems().get(0), other, "1", "50.00",
                "REJECTED", november.atTime(10, 0), null);

        KioskSalesByCategoryReportResponse oct = report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertMoney(row(oct, "Cuero").getTotalAmount(), "130.00");
        assertUnits(row(oct, "Cuero").getUnits(), "2");

        KioskSalesByCategoryReportResponse nov = report(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null);
        assertThat(nov.getRows()).hasSize(1);
        assertMoney(row(nov, "Cuero").getTotalAmount(), "-80.00");
        assertUnits(row(nov, "Cuero").getUnits(), "-1");
    }

    @Test
    void returnItemRowsWinOverABogusHeaderAndLegacyUsesTheHeader() throws Exception {
        ProductCategoryEntity cat = category("Filas");
        ProductEntity product = product("FIL-1", cat);
        LocalDate saleDay = LocalDate.of(2026, 2, 1);
        LocalDate returnDay = LocalDate.of(2026, 3, 1);
        KioskSaleEntity sale = sale(kiosk, saleDay, saleDay.atTime(9, 0), "COMPLETED", false, null,
                money("20.00"), money("80.00"),
                line(product, "FIL-1", "1", "100.00", "100.00"));
        KioskExchangeSlipEntity slip = headerReturn(kiosk, sale, sale.getItems().get(0), product,
                "9", "100.00", "COMPLETED", returnDay.atTime(8, 0), null);
        returnedItem(slip, sale.getItems().get(0), product, 1, "1", "100.00");

        ProductEntity legacy = product("FIL-2", cat);
        KioskSaleEntity legacySale = sale(kiosk, saleDay, saleDay.atTime(10, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("40.00"),
                line(legacy, "FIL-2", "2", "20.00", "40.00"));
        headerReturn(kiosk, legacySale, legacySale.getItems().get(0), legacy, "2", "40.00",
                "REINTEGRATED", returnDay.atTime(9, 0), null);

        KioskSalesByCategoryReportResponse march = report(returnDay, returnDay, null);
        assertUnits(row(march, "Filas").getUnits(), "-3");
        assertMoney(row(march, "Filas").getTotalAmount(), "-120.00");
    }

    @Test
    void secondReturnOfTheSameLineIsCappedAtZeroAmount() throws Exception {
        ProductCategoryEntity cat = category("Tope");
        ProductEntity product = product("TOP-1", cat);
        LocalDate day = LocalDate.of(2026, 4, 2);
        KioskSaleEntity sale = sale(kiosk, day, day.atTime(8, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(product, "TOP-1", "1", "100.00", "100.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", day.atTime(9, 0), null);
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "PENDING_REINTEGRO", day.atTime(10, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "Tope").getUnits(), "-1");
        assertMoney(row(report, "Tope").getTotalAmount(), "0.00");
    }

    @Test
    void earlierReturnOutsideTheWindowConsumesTheCap() throws Exception {
        ProductCategoryEntity cat = category("Previo");
        ProductEntity product = product("PRE-1", cat);
        LocalDate march = LocalDate.of(2026, 3, 10);
        LocalDate april = LocalDate.of(2026, 4, 10);
        KioskSaleEntity sale = sale(kiosk, march, march.atTime(8, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(product, "PRE-1", "1", "100.00", "100.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", march.atTime(18, 0), null);
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", april.atTime(9, 0), null);

        KioskSalesByCategoryReportResponse report = report(april, april, null);
        assertUnits(row(report, "Previo").getUnits(), "-1");
        assertMoney(row(report, "Previo").getTotalAmount(), "0.00");
    }

    @Test
    void completedExchangeMovesUnitsAndLeavesTheMoney() throws Exception {
        ProductCategoryEntity original = category("Original");
        ProductCategoryEntity given = category("Entregado");
        ProductEntity productA = product("EX-A", original);
        ProductEntity productB = product("EX-B", given);
        LocalDate day = LocalDate.of(2026, 5, 5);
        KioskSaleEntity saleA = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(productA, "EX-A", "1", "100.00", "100.00"));
        sale(kiosk, day, day.atTime(10, 0), "COMPLETED", false, null,
                money("100.00"), money("50.00"),
                line(productB, "EX-B", "1", "150.00", "150.00"));
        KioskExchangeSlipEntity exchange = headerReturn(kiosk, saleA, saleA.getItems().get(0), productA,
                "1", "100.00", "COMPLETED", day.atTime(11, 0), "EXCHANGE");
        returnedItem(exchange, saleA.getItems().get(0), productA, 1, "1", "100.00");
        headerReturn(kiosk, saleA, saleA.getItems().get(0), productA,
                "1", "100.00", "PENDING_AUTHORIZATION", day.atTime(12, 0), "EXCHANGE");
        headerReturn(kiosk, saleA, saleA.getItems().get(0), productA,
                "1", "100.00", "REJECTED", day.atTime(13, 0), "EXCHANGE");

        ProductCategoryEntity legacyCat = category("Legado");
        ProductEntity legacy = product("EX-L", legacyCat);
        KioskSaleEntity legacySale = sale(kiosk, day, day.atTime(14, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("30.00"),
                line(legacy, "EX-L", "1", "30.00", "30.00"));
        headerReturn(kiosk, legacySale, legacySale.getItems().get(0), legacy,
                "1", "30.00", "COMPLETED", day.atTime(15, 0), "EXCHANGE");

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "Original").getUnits(), "0");
        assertMoney(row(report, "Original").getTotalAmount(), "100.00");
        assertUnits(row(report, "Entregado").getUnits(), "1");
        assertMoney(row(report, "Entregado").getTotalAmount(), "50.00");
        assertUnits(row(report, "Legado").getUnits(), "0");
        assertMoney(row(report, "Legado").getTotalAmount(), "30.00");
        assertUnits(report.getTotals().getUnits(), "1");
        assertMoney(report.getTotals().getTotalAmount(), "180.00");
    }

    @Test
    void voidCancelledAndTestSalesAreExcludedAndBlankOrPendingStillCount() throws Exception {
        ProductCategoryEntity cat = category("Estado");
        ProductEntity product = product("EST-1", cat);
        LocalDate day = LocalDate.of(2026, 7, 1);
        sale(kiosk, day, day.atTime(8, 0), "VOID", false, null, BigDecimal.ZERO, money("100.00"),
                line(product, "EST-1", "1", "100.00", "100.00"));
        sale(kiosk, day, day.atTime(8, 5), "CANCELLED", false, null, BigDecimal.ZERO, money("70.00"),
                line(product, "EST-1", "1", "70.00", "70.00"));
        sale(kiosk, day, day.atTime(8, 10), "COMPLETED", true, null, BigDecimal.ZERO, money("200.00"),
                line(product, "EST-1", "1", "200.00", "200.00"));
        KioskSaleEntity nullStatus = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("11.00"),
                line(product, "EST-1", "1", "11.00", "11.00"));
        KioskSaleEntity blankStatus = sale(kiosk, day, day.atTime(9, 5), "COMPLETED", false, null,
                BigDecimal.ZERO, money("13.00"),
                line(product, "EST-1", "1", "13.00", "13.00"));
        sale(kiosk, day, day.atTime(10, 0), "PENDING", false, null, BigDecimal.ZERO, money("17.00"),
                line(product, "EST-1", "1", "17.00", "17.00"));
        sale(kiosk, day, day.atTime(11, 0), "COMPLETED", false, "VOID", BigDecimal.ZERO, money("19.00"),
                line(product, "EST-1", "1", "19.00", "19.00"));
        entityManager.flush();
        entityManager.clear();
        jdbc.update("UPDATE kiosk_sale SET status = NULL WHERE id = ?", nullStatus.getId());
        jdbc.update("UPDATE kiosk_sale SET status = ' ' WHERE id = ?", blankStatus.getId());

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertMoney(row(report, "Estado").getTotalAmount(), "60.00");
        assertUnits(row(report, "Estado").getUnits(), "4");
    }

    @Test
    void guatemalaDayUsesSaleDateAndHalfOpenSlipTimestamps() throws Exception {
        ProductCategoryEntity cat = category("Borde");
        ProductEntity product = product("BOR-1", cat);
        LocalDate last = LocalDate.of(2026, 1, 31);
        LocalDate next = LocalDate.of(2026, 2, 1);
        sale(kiosk, last, last.atTime(23, 30), "COMPLETED", false, null, BigDecimal.ZERO, money("7.00"),
                line(product, "BOR-1", "1", "7.00", "7.00"));
        sale(kiosk, next, next.atTime(0, 10), "COMPLETED", false, null, BigDecimal.ZERO, money("9.00"),
                line(product, "BOR-1", "1", "9.00", "9.00"));
        sale(kiosk, last, next.atTime(0, 10), "COMPLETED", false, null, BigDecimal.ZERO, money("5.00"),
                line(product, "BOR-1", "1", "5.00", "5.00"));

        LocalDate december = LocalDate.of(2025, 12, 20);
        KioskSaleEntity atNight = sale(kiosk, december, december.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("4.00"), line(product, "BOR-1", "1", "4.00", "4.00"));
        headerReturn(kiosk, atNight, atNight.getItems().get(0), product, "1", "4.00",
                "COMPLETED", last.atTime(23, 30), null);
        KioskSaleEntity afterMidnight = sale(kiosk, december, december.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("6.00"), line(product, "BOR-1", "1", "6.00", "6.00"));
        headerReturn(kiosk, afterMidnight, afterMidnight.getItems().get(0), product, "1", "6.00",
                "COMPLETED", next.atTime(0, 10), null);
        KioskSaleEntity byCreated = sale(kiosk, december, december.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("3.00"), line(product, "BOR-1", "1", "3.00", "3.00"));
        headerReturn(kiosk, byCreated, byCreated.getItems().get(0), product, "1", "3.00",
                "COMPLETED", null, null).setCreatedAt(last.atTime(22, 0));
        slipRepository.saveAndFlush(slipOf(byCreated));
        KioskSaleEntity createdNext = sale(kiosk, december, december.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("8.00"), line(product, "BOR-1", "1", "8.00", "8.00"));
        KioskExchangeSlipEntity nextCreated = headerReturn(kiosk, createdNext, createdNext.getItems().get(0), product,
                "1", "8.00", "COMPLETED", null, null);
        nextCreated.setCreatedAt(next.atTime(0, 5));
        slipRepository.saveAndFlush(nextCreated);

        KioskSalesByCategoryReportResponse report = report(last, last, null);
        assertMoney(row(report, "Borde").getTotalAmount(), "5.00");
        assertUnits(row(report, "Borde").getUnits(), "0");
    }

    @Test
    void kioskIdsFilterAndPilotIsExcludedUnlessAsked() throws Exception {
        LocationEntity other = kiosk("K" + SEQ.incrementAndGet(), false);
        LocationEntity pilot = kiosk("P" + SEQ.incrementAndGet(), true);
        ProductCategoryEntity cat = category("Local");
        ProductEntity product = product("LOC-1", cat);
        LocalDate day = LocalDate.of(2026, 8, 1);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product, "LOC-1", "1", "10.00", "10.00"));
        sale(other, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("30.00"),
                line(product, "LOC-1", "1", "30.00", "30.00"));
        sale(pilot, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("1000.00"),
                line(product, "LOC-1", "1", "1000.00", "1000.00"));

        assertMoney(row(report(day, day, String.valueOf(kiosk.getId())), "Local").getTotalAmount(), "10.00");
        assertMoney(row(report(day, day, null), "Local").getTotalAmount(), "40.00");
        assertMoney(row(report(day, day, String.valueOf(pilot.getId())), "Local").getTotalAmount(), "1000.00");
    }

    @Test
    void productWithoutCategoryIsSinCategoria() throws Exception {
        ProductEntity product = product("SIN-1", null);
        LocalDate day = LocalDate.of(2026, 8, 2);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("12.00"),
                line(product, "SIN-1", "1", "12.00", "12.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        Row row = row(report, "Sin categoría");
        assertThat(row.getCategoryId()).isNull();
        assertMoney(row.getTotalAmount(), "12.00");
    }

    @Test
    void negativeRowAndIvaAreNotTheSumOfRoundedRows() throws Exception {
        ProductCategoryEntity a = category("IvaA");
        ProductCategoryEntity b = category("IvaB");
        ProductCategoryEntity c = category("IvaC");
        LocalDate day = LocalDate.of(2026, 9, 1);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("1.00"),
                line(product("IVA-A", a), "IVA-A", "1", "1.00", "1.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("1.00"),
                line(product("IVA-B", b), "IVA-B", "1", "1.00", "1.00"));
        sale(kiosk, day, day.atTime(9, 2), "COMPLETED", false, null, BigDecimal.ZERO, money("1.00"),
                line(product("IVA-C", c), "IVA-C", "1", "1.00", "1.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).extracting(Row::getCategoryName).containsExactly("IvaA", "IvaB", "IvaC");
        BigDecimal rowNoIva = BigDecimal.ZERO;
        for (Row row : report.getRows()) {
            assertMoney(row.getTotalAmountNoIva(), "0.89");
            rowNoIva = rowNoIva.add(row.getTotalAmountNoIva());
        }
        assertMoney(rowNoIva, "2.67");
        assertMoney(report.getTotals().getTotalAmount(), "3.00");
        assertMoney(report.getTotals().getTotalAmountNoIva(), "2.68");
        assertUnits(report.getTotals().getUnits(), "3");
    }

    @Test
    void invalidDatesAndKioskIdsAre400BeforeAuth() throws Exception {
        when(securityUtil.getCurrentUserId()).thenThrow(new IllegalStateException("auth"));

        mockMvc.perform(get("/api/reports/kiosk-sales-by-category").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'from' falta o tiene un formato inválido."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-13-40").param("to", "2026-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'from' falta o tiene un formato inválido."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-02-02").param("to", "2026-02-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha inicial no puede ser posterior a la fecha final."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2024-01-01").param("to", "2025-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El rango no puede superar 366 días."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-01").param("kioskIds", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los identificadores de kiosko no son válidos."));
    }

    @Test
    void rangeOf366DaysIsAllowedAndUnknownKioskIs400() throws Exception {
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2024-01-01").param("to", "2025-01-01"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-01").param("kioskIds", "999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El kiosko seleccionado no existe o no es un kiosko."));
    }

    @Test
    void omittedKiosksWithOnlyAPilotReturns400() throws Exception {
        locationRepository.delete(kiosk);
        kiosk("SOLOP", true);
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No hay kioskos para generar el reporte."));
    }

    @Test
    void walletsSplitByAudienceAndReturnsUseTheSameClassification() throws Exception {
        ProductCategoryEntity cat = category("Billeteras");
        ProductEntity dama = product("BILL-D", cat, "Billetera dama", "DAMA", null, false);
        ProductEntity caballero = product("BILL-C", cat, "Billetera caballero", "CABALLERO", null, false);
        ProductEntity unisex = product("BILL-U", cat, "Billetera unisex", "UNISEX", null, false);
        LocalDate day = LocalDate.of(2026, 6, 10);
        KioskSaleEntity damaSale = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(dama, dama.getCode(), "1", "100.00", "100.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("80.00"),
                line(caballero, caballero.getCode(), "1", "80.00", "80.00"));
        sale(kiosk, day, day.atTime(9, 2), "COMPLETED", false, null, BigDecimal.ZERO, money("20.00"),
                line(unisex, unisex.getCode(), "1", "20.00", "20.00"));
        headerReturn(kiosk, damaSale, damaSale.getItems().get(0), dama, "1", "100.00",
                "COMPLETED", day.atTime(11, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(3);
        Row damaRow = row(report, "Billeteras", "DAMA");
        assertThat(damaRow.getSubgroupLabel()).isEqualTo("Dama");
        assertUnits(damaRow.getUnits(), "0");
        assertMoney(damaRow.getTotalAmount(), "0.00");
        Row cabRow = row(report, "Billeteras", "CABALLERO");
        assertThat(cabRow.getSubgroupLabel()).isEqualTo("Caballero");
        assertMoney(cabRow.getTotalAmount(), "80.00");
        Row unclassified = row(report, "Billeteras", "SIN_CLASIFICAR");
        assertThat(unclassified.getSubgroupLabel()).isEqualTo("Sin clasificar");
        assertMoney(unclassified.getTotalAmount(), "20.00");
        assertMoney(sumAmounts(report), "100.00");
        assertMoney(report.getTotals().getTotalAmount(), "100.00");
    }

    @Test
    void beltsSplitIncludingSinClasificarAndSumToGrandTotal() throws Exception {
        ProductCategoryEntity cat = category("Cinchos");
        LocalDate day = LocalDate.of(2026, 6, 11);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("50.00"),
                line(product("FOSS-N", cat, "Cincho niño", "DAMA", "CASUAL", true), "FOSS-N", "1", "50.00", "50.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product("FOSS-DC", cat, "Cincho dama casual", "DAMA", "CASUAL", false),
                        "FOSS-DC", "1", "40.00", "40.00"));
        sale(kiosk, day, day.atTime(9, 2), "COMPLETED", false, null, BigDecimal.ZERO, money("30.00"),
                line(product("FOSS-CC", cat, "Cincho caballero casual", "CABALLERO", "CASUAL", false),
                        "FOSS-CC", "1", "30.00", "30.00"));
        sale(kiosk, day, day.atTime(9, 3), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product("FOSS-U", cat, "Cincho unisex", "UNISEX", "CASUAL", false),
                        "FOSS-U", "1", "10.00", "10.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(4);
        assertMoney(row(report, "Cinchos", "NINO").getTotalAmount(), "50.00");
        assertThat(row(report, "Cinchos", "NINO").getSubgroupLabel()).isEqualTo("Niño");
        assertMoney(row(report, "Cinchos", "DAMA_CASUAL").getTotalAmount(), "40.00");
        assertMoney(row(report, "Cinchos", "CABALLERO_CASUAL").getTotalAmount(), "30.00");
        assertMoney(row(report, "Cinchos", "SIN_CLASIFICAR").getTotalAmount(), "10.00");
        assertThat(row(report, "Cinchos", "SIN_CLASIFICAR").getSubgroupLabel()).isEqualTo("Sin clasificar");
        assertMoney(sumAmounts(report), "130.00");
        assertMoney(report.getTotals().getTotalAmount(), "130.00");
        assertUnits(report.getTotals().getUnits(), "4");
    }

    @Test
    void kidsCinchoTakesPrecedenceOverAudienceAndType() throws Exception {
        ProductCategoryEntity cat = category("CinchosKids");
        LocalDate day = LocalDate.of(2026, 6, 12);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("15.00"),
                line(product("FOSS-K", cat, "Cincho dama casual kids", "DAMA", "CASUAL", true),
                        "FOSS-K", "1", "15.00", "15.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("17.00"),
                line(product("N-113-JR", cat, "Cincho JR reversible", "CABALLERO", "REVERSIBLE", false),
                        "N-113-JR", "1", "17.00", "17.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(1);
        Row nino = row(report, "CinchosKids", "NINO");
        assertThat(nino.getSubgroupLabel()).isEqualTo("Niño");
        assertMoney(nino.getTotalAmount(), "32.00");
        assertUnits(nino.getUnits(), "2");
    }

    @Test
    void reversibleBeltMapsToFormal() throws Exception {
        ProductCategoryEntity cat = category("CinchosFormal");
        LocalDate day = LocalDate.of(2026, 6, 13);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("22.00"),
                line(product("FOSS-DF", cat, "Cincho dama reversible", "DAMA", "REVERSIBLE", false),
                        "FOSS-DF", "1", "22.00", "22.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("28.00"),
                line(product("FOSS-CF", cat, "Cincho caballero reversible", "CABALLERO", "REVERSIBLE", false),
                        "FOSS-CF", "1", "28.00", "28.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertMoney(row(report, "CinchosFormal", "DAMA_FORMAL").getTotalAmount(), "22.00");
        assertThat(row(report, "CinchosFormal", "DAMA_FORMAL").getSubgroupLabel()).isEqualTo("Dama formal");
        assertMoney(row(report, "CinchosFormal", "CABALLERO_FORMAL").getTotalAmount(), "28.00");
        assertThat(row(report, "CinchosFormal", "CABALLERO_FORMAL").getSubgroupLabel())
                .isEqualTo("Caballero formal");
    }

    @Test
    void unisexGoesToSinClasificar() throws Exception {
        ProductCategoryEntity wallets = category("BilleterasUnisex");
        ProductCategoryEntity belts = category("CinchosUnisex");
        LocalDate day = LocalDate.of(2026, 6, 14);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("9.00"),
                line(product("BILL-U2", wallets, "Billetera unisex", "UNISEX", null, false),
                        "BILL-U2", "1", "9.00", "9.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("11.00"),
                line(product("FOSS-U2", belts, "Cincho unisex", "UNISEX", "CASUAL", false),
                        "FOSS-U2", "1", "11.00", "11.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(row(report, "BilleterasUnisex", "SIN_CLASIFICAR").getSubgroupLabel())
                .isEqualTo("Sin clasificar");
        assertMoney(row(report, "BilleterasUnisex", "SIN_CLASIFICAR").getTotalAmount(), "9.00");
        assertThat(row(report, "CinchosUnisex", "SIN_CLASIFICAR").getSubgroupLabel())
                .isEqualTo("Sin clasificar");
        assertMoney(row(report, "CinchosUnisex", "SIN_CLASIFICAR").getTotalAmount(), "11.00");
    }

    @Test
    void damaBeltWithNullCinchoTypeIsSinClasificar() throws Exception {
        ProductCategoryEntity cat = category("CinchosNullType");
        LocalDate day = LocalDate.of(2026, 6, 15);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("18.00"),
                line(product("FOSS-DNULL", cat, "Cincho dama sin tipo", "DAMA", null, false),
                        "FOSS-DNULL", "1", "18.00", "18.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        Row row = row(report, "CinchosNullType", "SIN_CLASIFICAR");
        assertThat(row.getSubgroupLabel()).isEqualTo("Sin clasificar");
        assertMoney(row.getTotalAmount(), "18.00");
    }

    @Test
    void nonSplitCategoryHasNullSubgroupFields() throws Exception {
        ProductCategoryEntity cat = category("Bolsas");
        LocalDate day = LocalDate.of(2026, 6, 16);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("12.00"),
                line(product("BOL-1", cat), "BOL-1", "1", "12.00", "12.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(1);
        Row row = row(report, "Bolsas");
        assertThat(row.getSubgroupCode()).isNull();
        assertThat(row.getSubgroupLabel()).isNull();
        assertMoney(row.getTotalAmount(), "12.00");
    }

    @Test
    void entrecuerosIsExcludedFromSalesAndReturns() throws Exception {
        LocationEntity entrecueros = kiosk("ETRC" + SEQ.incrementAndGet(), false, "ENTRECUEROS");
        ProductCategoryEntity cat = category("CueroExcl");
        ProductEntity product = product("CUO-EX", cat);
        LocalDate day = LocalDate.of(2026, 6, 17);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("100.00"),
                line(product, "CUO-EX", "1", "100.00", "100.00"));
        sale(entrecueros, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("999.00"),
                line(product, "CUO-EX", "1", "999.00", "999.00"));
        KioskSaleEntity regularReturnSale = sale(kiosk, day, day.atTime(8, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("40.00"),
                line(product, "CUO-EX", "1", "40.00", "40.00"));
        headerReturn(kiosk, regularReturnSale, regularReturnSale.getItems().get(0), product,
                "1", "40.00", "COMPLETED", day.atTime(12, 0), null);
        KioskSaleEntity entrecuerosReturnSale = sale(entrecueros, day, day.atTime(8, 1), "COMPLETED", false, null,
                BigDecimal.ZERO, money("50.00"),
                line(product, "CUO-EX", "1", "50.00", "50.00"));
        headerReturn(entrecueros, entrecuerosReturnSale, entrecuerosReturnSale.getItems().get(0), product,
                "1", "50.00", "COMPLETED", day.atTime(12, 1), null);

        KioskSalesByCategoryReportResponse all = report(day, day, null);
        assertMoney(row(all, "CueroExcl").getTotalAmount(), "100.00");
        assertUnits(row(all, "CueroExcl").getUnits(), "1");
        assertThat(row(all, "CueroExcl").getSubgroupCode()).isNull();

        KioskSalesByCategoryReportResponse bothIds = report(day, day,
                kiosk.getId() + "," + entrecueros.getId());
        assertMoney(row(bothIds, "CueroExcl").getTotalAmount(), "100.00");

        KioskSalesByCategoryReportResponse onlyEntrecueros = report(day, day,
                String.valueOf(entrecueros.getId()));
        assertThat(onlyEntrecueros.getRows()).isEmpty();
        assertMoney(onlyEntrecueros.getTotals().getTotalAmount(), "0.00");
    }

    @Test
    void userWithoutReportsAccessGets403() throws Exception {
        UserEntity seller = userWithRole("VENDEDOR");
        when(securityUtil.getCurrentUserId()).thenReturn(seller.getId());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Solo administradores, logística o contabilidad pueden ver este reporte."));
    }

    private KioskSalesByCategoryReportResponse report(LocalDate from, LocalDate to, String kioskIds) throws Exception {
        var request = get("/api/reports/kiosk-sales-by-category")
                .param("from", from.toString())
                .param("to", to.toString());
        if (kioskIds != null) {
            request = request.param("kioskIds", kioskIds);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), KioskSalesByCategoryReportResponse.class);
    }

    private UserEntity userWithRole(String roleName) {
        RoleEntity role = roleRepository.save(RoleEntity.builder().name(roleName + "-" + SEQ.incrementAndGet()).build());
        if ("ADMIN".equals(roleName)) {
            role.setName("ADMIN");
            role = roleRepository.save(role);
        }
        UserEntity user = UserEntity.builder()
                .username("u" + SEQ.incrementAndGet())
                .email("u" + SEQ.get() + "@t.local")
                .password("x")
                .roles(new HashSet<>(Set.of(role)))
                .build();
        return userRepository.saveAndFlush(user);
    }

    private LocationEntity kiosk(String code, boolean pilot) {
        return kiosk(code, pilot, "STANDARD");
    }

    private LocationEntity kiosk(String code, boolean pilot, String posMode) {
        return locationRepository.saveAndFlush(LocationEntity.builder()
                .code(code)
                .name("Kiosko " + code)
                .categoria("KIOSKO")
                .posTestMode(pilot)
                .posMode(posMode)
                .posOpeningCashAmount(new BigDecimal("300.00"))
                .build());
    }

    private ProductCategoryEntity category(String name) {
        return categoryRepository.saveAndFlush(ProductCategoryEntity.builder()
                .code("C" + SEQ.incrementAndGet())
                .name(name)
                .build());
    }

    private ProductEntity product(String code, ProductCategoryEntity category) {
        return product(code, category, code, null, null, false);
    }

    private ProductEntity product(
            String code,
            ProductCategoryEntity category,
            String name,
            String audience,
            String cinchoType,
            Boolean kids
    ) {
        return productRepository.saveAndFlush(ProductEntity.builder()
                .code(code + "-" + SEQ.incrementAndGet())
                .name(name)
                .categoryId(category == null ? null : category.getId())
                .audienceCategory(audience)
                .cinchoType(cinchoType)
                .cinchoForKids(Boolean.TRUE.equals(kids))
                .build());
    }

    private KioskSaleItemEntity line(ProductEntity product, String code, String qty, String price, String total) {
        return KioskSaleItemEntity.builder()
                .productId(product.getId())
                .productCode(code)
                .quantity(new BigDecimal(qty))
                .unitPrice(money(price))
                .lineTotal(money(total))
                .build();
    }

    private KioskSaleEntity sale(
            LocationEntity location,
            LocalDate saleDate,
            LocalDateTime soldAt,
            String status,
            boolean testSale,
            String felStatus,
            BigDecimal discount,
            BigDecimal total,
            KioskSaleItemEntity... items
    ) {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .saleNumber("S-" + SEQ.incrementAndGet())
                .kioskLocationId(location.getId())
                .soldByUserId(admin.getId())
                .saleDate(saleDate)
                .soldAt(soldAt)
                .status(status)
                .testSale(testSale)
                .felStatus(felStatus)
                .discountAmount(discount)
                .subtotal(total.add(discount))
                .totalAmount(total)
                .totalItems(BigDecimal.valueOf(items.length))
                .build();
        for (KioskSaleItemEntity item : items) {
            item.setKioskSale(sale);
            sale.getItems().add(item);
        }
        return saleRepository.saveAndFlush(sale);
    }

    private KioskExchangeSlipEntity headerReturn(
            LocationEntity location,
            KioskSaleEntity sale,
            KioskSaleItemEntity item,
            ProductEntity product,
            String qty,
            String listAmount,
            String status,
            LocalDateTime completedAt,
            String slipType
    ) {
        KioskExchangeSlipEntity slip = KioskExchangeSlipEntity.builder()
                .slipNumber("R-" + SEQ.incrementAndGet())
                .seriesCode("K")
                .slipType(slipType == null ? "RETURN" : slipType)
                .kioskLocationId(location.getId())
                .originalSaleId(sale.getId())
                .originalSaleItemId(item.getId())
                .returnedProductId(product.getId())
                .returnedQuantity(new BigDecimal(qty))
                .returnedAmount(money(listAmount))
                .status(status)
                .completedAt(completedAt)
                .build();
        return slipRepository.saveAndFlush(slip);
    }

    private void returnedItem(
            KioskExchangeSlipEntity slip,
            KioskSaleItemEntity item,
            ProductEntity product,
            int lineNo,
            String qty,
            String lineTotal
    ) {
        returnedItemRepository.saveAndFlush(KioskExchangeSlipReturnedItemEntity.builder()
                .exchangeSlipId(slip.getId())
                .lineNo(lineNo)
                .originalSaleItemId(item.getId())
                .productId(product.getId())
                .quantity(new BigDecimal(qty))
                .unitPrice(money(lineTotal))
                .lineTotal(money(lineTotal))
                .build());
    }

    private KioskExchangeSlipEntity slipOf(KioskSaleEntity sale) {
        return slipRepository.findAll().stream()
                .filter(slip -> sale.getId().equals(slip.getOriginalSaleId()))
                .findFirst()
                .orElseThrow();
    }

    private static Row row(KioskSalesByCategoryReportResponse report, String name) {
        return report.getRows().stream()
                .filter(row -> name.equals(row.getCategoryName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sin fila " + name + " en " + report.getRows()));
    }

    private static Row row(KioskSalesByCategoryReportResponse report, String name, String subgroupCode) {
        return report.getRows().stream()
                .filter(row -> name.equals(row.getCategoryName())
                        && Objects.equals(subgroupCode, row.getSubgroupCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Sin fila " + name + "/" + subgroupCode + " en " + report.getRows()));
    }

    private static BigDecimal sumAmounts(KioskSalesByCategoryReportResponse report) {
        return report.getRows().stream()
                .map(Row::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private static void assertUnits(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
