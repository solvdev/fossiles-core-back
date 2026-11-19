package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Row;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cases not covered by {@link KioskSalesByCategoryReportH2Test}.
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
class KioskSalesByCategoryReportQaTest {

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
    private OnlineSaleRepository onlineSaleRepository;

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
    void negativeCategoryRowStaysAndSortsByTotalAmountDesc() throws Exception {
        ProductCategoryEntity high = category("Alta");
        ProductCategoryEntity mid = category("Media");
        ProductCategoryEntity low = category("Negativa");
        LocalDate day = LocalDate.of(2026, 3, 8);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("100.00"),
                line(product("NEG-H", high), "NEG-H", "1", "100.00", "100.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product("NEG-M", mid), "NEG-M", "1", "10.00", "10.00"));
        ProductEntity returned = product("NEG-L", low);
        LocalDate earlier = day.minusDays(3);
        KioskSaleEntity prior = sale(kiosk, earlier, earlier.atTime(9, 2), "COMPLETED", false, null,
                BigDecimal.ZERO, money("50.00"),
                line(returned, "NEG-L", "1", "50.00", "50.00"));
        headerReturn(kiosk, prior, prior.getItems().get(0), returned, "1", "50.00",
                "COMPLETED", day.atTime(11, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).extracting(Row::getCategoryName)
                .containsExactly("Alta", "Media", "Negativa");
        assertMoney(row(report, "Negativa").getTotalAmount(), "-50.00");
        assertUnits(row(report, "Negativa").getUnits(), "-1");
        assertUnits(report.getTotals().getUnits(), "1");
        assertMoney(report.getTotals().getTotalAmount(), "60.00");
    }

    @Test
    void returnBoundariesUseInclusiveFromAndHalfOpenToPlusOneAtExactMidnight() throws Exception {
        ProductCategoryEntity cat = category("Medianoche");
        ProductEntity product = product("MID-1", cat);
        LocalDate from = LocalDate.of(2026, 6, 10);
        LocalDate before = from.minusDays(1);
        LocalDate after = from.plusDays(1);

        sale(kiosk, from, from.atTime(0, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("11.00"),
                line(product, "MID-1", "1", "11.00", "11.00"));
        sale(kiosk, before, before.atTime(23, 59), "COMPLETED", false, null, BigDecimal.ZERO, money("13.00"),
                line(product, "MID-1", "1", "13.00", "13.00"));
        sale(kiosk, after, after.atTime(0, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("17.00"),
                line(product, "MID-1", "1", "17.00", "17.00"));

        KioskSaleEntity includedStart = sale(kiosk, before, before.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("4.00"), line(product, "MID-1", "1", "4.00", "4.00"));
        headerReturn(kiosk, includedStart, includedStart.getItems().get(0), product, "1", "4.00",
                "COMPLETED", from.atTime(0, 0), null);
        KioskSaleEntity includedEnd = sale(kiosk, before, before.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("5.00"), line(product, "MID-1", "1", "5.00", "5.00"));
        headerReturn(kiosk, includedEnd, includedEnd.getItems().get(0), product, "1", "5.00",
                "COMPLETED", from.atTime(23, 59), null);
        KioskSaleEntity excludedAfter = sale(kiosk, before, before.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("7.00"), line(product, "MID-1", "1", "7.00", "7.00"));
        headerReturn(kiosk, excludedAfter, excludedAfter.getItems().get(0), product, "1", "7.00",
                "COMPLETED", after.atTime(0, 0), null);
        KioskSaleEntity excludedBefore = sale(kiosk, before, before.atTime(12, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("8.00"), line(product, "MID-1", "1", "8.00", "8.00"));
        headerReturn(kiosk, excludedBefore, excludedBefore.getItems().get(0), product, "1", "8.00",
                "COMPLETED", before.atTime(23, 59), null);

        KioskSalesByCategoryReportResponse report = report(from, from, null);
        assertMoney(row(report, "Medianoche").getTotalAmount(), "2.00");
        assertUnits(row(report, "Medianoche").getUnits(), "-1");
    }

    @Test
    void monthRangeIsInclusiveForLeapNonLeapAndThirtyOneDayMonths() throws Exception {
        ProductCategoryEntity cat = category("Mes");
        ProductEntity product = product("MES-1", cat);

        sale(kiosk, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("1.00"),
                line(product, "MES-1", "1", "1.00", "1.00"));
        sale(kiosk, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("2.00"),
                line(product, "MES-1", "1", "2.00", "2.00"));
        sale(kiosk, LocalDate.of(2024, 1, 31), LocalDate.of(2024, 1, 31).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product, "MES-1", "1", "40.00", "40.00"));
        sale(kiosk, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product, "MES-1", "1", "40.00", "40.00"));

        KioskSalesByCategoryReportResponse leap = report(
                LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29), null);
        assertMoney(row(leap, "Mes").getTotalAmount(), "3.00");
        assertUnits(row(leap, "Mes").getUnits(), "2");

        sale(kiosk, LocalDate.of(2025, 2, 28), LocalDate.of(2025, 2, 28).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("5.00"),
                line(product, "MES-1", "1", "5.00", "5.00"));
        sale(kiosk, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 1).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product, "MES-1", "1", "40.00", "40.00"));
        KioskSalesByCategoryReportResponse nonLeap = report(
                LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28), null);
        assertMoney(row(nonLeap, "Mes").getTotalAmount(), "5.00");

        sale(kiosk, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("7.00"),
                line(product, "MES-1", "1", "7.00", "7.00"));
        sale(kiosk, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1).atTime(10, 0),
                "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product, "MES-1", "1", "40.00", "40.00"));
        KioskSalesByCategoryReportResponse january = report(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);
        assertMoney(row(january, "Mes").getTotalAmount(), "7.00");
    }

    @Test
    void emptyKioskIdsMeansAllAndSeveralIdsExcludeTheRest() throws Exception {
        LocationEntity second = kiosk("K" + SEQ.incrementAndGet(), false);
        LocationEntity third = kiosk("K" + SEQ.incrementAndGet(), false);
        ProductCategoryEntity cat = category("Filtro");
        ProductEntity product = product("FILT-1", cat);
        LocalDate day = LocalDate.of(2026, 4, 4);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product, "FILT-1", "1", "10.00", "10.00"));
        sale(second, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("20.00"),
                line(product, "FILT-1", "1", "20.00", "20.00"));
        sale(third, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product, "FILT-1", "1", "40.00", "40.00"));

        assertMoney(row(report(day, day, ""), "Filtro").getTotalAmount(), "70.00");
        assertMoney(row(report(day, day, kiosk.getId() + "," + second.getId()), "Filtro").getTotalAmount(), "30.00");
        assertMoney(row(report(day, day, second.getId() + ", " + third.getId()), "Filtro").getTotalAmount(), "60.00");
    }

    @Test
    void unknownKioskIdReturns400WithSpanishMessage() throws Exception {
        ProductCategoryEntity cat = category("Desconocido");
        ProductEntity product = product("UNK-1", cat);
        LocalDate day = LocalDate.of(2026, 4, 5);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product, "UNK-1", "1", "10.00", "10.00"));

        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .param("kioskIds", kiosk.getId() + ",999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El kiosko seleccionado no existe o no es un kiosko."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .param("kioskIds", "999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El kiosko seleccionado no existe o no es un kiosko."));
    }

    @Test
    void headerDiscountPartialReturnSubtractsProratedPaidPrice() throws Exception {
        ProductCategoryEntity catA = category("LineaA");
        ProductCategoryEntity catB = category("LineaB");
        ProductEntity productA = product("HDR-A", catA);
        ProductEntity productB = product("HDR-B", catB);
        LocalDate day = LocalDate.of(2026, 5, 12);
        KioskSaleEntity sale = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                money("20.00"), money("180.00"),
                line(productA, "HDR-A", "1", "100.00", "100.00"),
                line(productB, "HDR-B", "1", "100.00", "100.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), productA, "1", "100.00",
                "COMPLETED", day.atTime(10, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "LineaA").getUnits(), "0");
        assertMoney(row(report, "LineaA").getTotalAmount(), "0.00");
        assertUnits(row(report, "LineaB").getUnits(), "1");
        assertMoney(row(report, "LineaB").getTotalAmount(), "90.00");
        assertMoney(report.getTotals().getTotalAmount(), "90.00");
    }

    @Test
    void itemDiscountPartialReturnProratesPerUnitAndIgnoresHeader() throws Exception {
        ProductCategoryEntity cat = category("ItemDesc");
        ProductEntity product = product("ITM-1", cat);
        LocalDate day = LocalDate.of(2026, 5, 13);
        KioskSaleEntity sale = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                money("99.00"), money("180.00"),
                line(product, "ITM-1", "2", "100.00", "200.00", "20.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", day.atTime(11, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "ItemDesc").getUnits(), "1");
        assertMoney(row(report, "ItemDesc").getTotalAmount(), "90.00");
    }

    @Test
    void saleWithDiscountPlusFullReturnNetsToZero() throws Exception {
        ProductCategoryEntity cat = category("NetoCero");
        ProductEntity product = product("NET-1", cat);
        LocalDate day = LocalDate.of(2026, 5, 14);
        KioskSaleEntity sale = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                money("20.00"), money("80.00"),
                line(product, "NET-1", "1", "100.00", "100.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", day.atTime(12, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "NetoCero").getUnits(), "0");
        assertMoney(row(report, "NetoCero").getTotalAmount(), "0.00");
        assertMoney(report.getTotals().getTotalAmount(), "0.00");
    }

    @Test
    void categoryTotalsMatchSumOfKioskSaleTotalAmount() throws Exception {
        ProductCategoryEntity catA = category("SumaA");
        ProductCategoryEntity catB = category("SumaB");
        ProductCategoryEntity catC = category("SumaC");
        LocalDate day = LocalDate.of(2026, 5, 15);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("85.00"),
                line(product("TOT-A", catA), "TOT-A", "1", "100.00", "100.00", "15.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, money("15.00"), money("135.00"),
                line(product("TOT-B1", catB), "TOT-B1", "1", "100.00", "100.00"),
                line(product("TOT-C1", catC), "TOT-C1", "1", "50.00", "50.00"));
        sale(kiosk, day, day.atTime(9, 2), "COMPLETED", false, null, BigDecimal.ZERO, money("40.00"),
                line(product("TOT-C2", catC), "TOT-C2", "1", "40.00", "40.00"));

        BigDecimal dbSum = jdbc.queryForObject(
                """
                        SELECT COALESCE(SUM(total_amount), 0)
                        FROM kiosk_sale
                        WHERE sale_date = ?
                          AND kiosk_location_id = ?
                          AND COALESCE(test_sale, FALSE) = FALSE
                          AND COALESCE(UPPER(TRIM(status)), '') NOT IN ('VOID', 'CANCELLED')
                        """,
                BigDecimal.class,
                day,
                kiosk.getId());

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertMoney(sumAmounts(report), dbSum.toPlainString());
        assertMoney(report.getTotals().getTotalAmount(), dbSum.toPlainString());
        assertMoney(row(report, "SumaA").getTotalAmount(), "85.00");
        assertMoney(row(report, "SumaB").getTotalAmount(), "90.00");
        assertMoney(row(report, "SumaC").getTotalAmount(), "85.00");
    }

    @Test
    void fractionalUnitsAreSummedExactly() throws Exception {
        ProductCategoryEntity cat = category("Fraccion");
        ProductEntity product = product("FRC-1", cat);
        LocalDate day = LocalDate.of(2026, 5, 16);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("15.00"),
                line(product, "FRC-1", "1.500", "10.00", "15.00"));
        sale(kiosk, day, day.atTime(9, 1), "COMPLETED", false, null, BigDecimal.ZERO, money("2.50"),
                line(product, "FRC-1", "0.250", "10.00", "2.50"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "Fraccion").getUnits(), "1.75");
        assertUnits(report.getTotals().getUnits(), "1.75");
        assertMoney(report.getTotals().getTotalAmount(), "17.50");
    }

    @Test
    void amountsHaveScaleTwoAndIvaIsIncludedInTotalAmount() throws Exception {
        ProductCategoryEntity cat = category("Iva");
        LocalDate day = LocalDate.of(2026, 5, 17);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("112.00"),
                line(product("IVA-1", cat), "IVA-1", "1", "112.00", "112.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        Row row = row(report, "Iva");
        assertThat(row.getTotalAmount().scale()).isEqualTo(2);
        assertThat(row.getTotalAmountNoIva().scale()).isEqualTo(2);
        assertThat(report.getTotals().getTotalAmount().scale()).isEqualTo(2);
        assertThat(report.getTotals().getTotalAmountNoIva().scale()).isEqualTo(2);
        assertMoney(row.getTotalAmount(), "112.00");
        assertMoney(row.getTotalAmountNoIva(), "100.00");
        assertMoney(report.getTotals().getTotalAmountNoIva(), "100.00");
    }

    @Test
    void missingToIs400AndFromEqualsToIsOk() throws Exception {
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category").param("from", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'to' falta o tiene un formato inválido."));
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-01"))
                .andExpect(status().isOk());
    }

    @Test
    void emptyPeriodReturnsNoRowsZeroTotalsAndEchoesDates() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 15);
        KioskSalesByCategoryReportResponse report = report(from, to, null);
        assertThat(report.getFrom()).isEqualTo(from);
        assertThat(report.getTo()).isEqualTo(to);
        assertThat(report.getRows()).isEmpty();
        assertUnits(report.getTotals().getUnits(), "0");
        assertMoney(report.getTotals().getTotalAmount(), "0.00");
        assertMoney(report.getTotals().getTotalAmountNoIva(), "0.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "LOGISTICA", "CONTABILIDAD"})
    void kioskReportsRolesCanRead(String roleName) throws Exception {
        UserEntity user = "ADMIN".equals(roleName) ? admin : userWithRole(roleName);
        when(securityUtil.getCurrentUserId()).thenReturn(user.getId());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk());
    }

    @Test
    void encargadaAndMissingUserAreForbidden() throws Exception {
        UserEntity encargada = userWithRole("ENCARGADA_KIOSKO");
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Solo administradores, logística o contabilidad pueden ver este reporte."));

        when(securityUtil.getCurrentUserId()).thenReturn(null);
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("No se pudo identificar el usuario autenticado."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENTA_EN_LINEA", "SUPERVISORA_KIOSKO"})
    void extraGlobalKioskRolesShouldBeForbidden(String roleName) throws Exception {
        UserEntity user = userWithRole(roleName);
        when(securityUtil.getCurrentUserId()).thenReturn(user.getId());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Solo administradores, logística o contabilidad pueden ver este reporte."));
    }

    @Test
    void plainUserIsForbidden() throws Exception {
        UserEntity user = userWithRole("USUARIO");
        when(securityUtil.getCurrentUserId()).thenReturn(user.getId());
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Solo administradores, logística o contabilidad pueden ver este reporte."));
    }

    @Test
    void onlineStoreAndDistributorSalesAreExcluded() throws Exception {
        LocationEntity store = location("WEB", "Tienda en línea", "TIENDA");
        LocationEntity distributor = location("DIS1", "Mayorista zona 1", "DISTRIBUIDOR");
        ProductCategoryEntity cat = category("Canal");
        ProductEntity product = product("CAN-1", cat);
        LocalDate day = LocalDate.of(2026, 7, 7);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("10.00"),
                line(product, "CAN-1", "1", "10.00", "10.00"));
        sale(store, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("500.00"),
                line(product, "CAN-1", "1", "500.00", "500.00"));
        sale(distributor, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("700.00"),
                line(product, "CAN-1", "1", "700.00", "700.00"));
        onlineSaleRepository.saveAndFlush(OnlineSaleEntity.builder()
                .saleNumber("ON-1")
                .productId(product.getId())
                .productCode(product.getCode())
                .quantity(1)
                .unitPrice(money("900.00"))
                .netAmount(money("900.00"))
                .totalAmount(money("915.00"))
                .saleDate(day)
                .status("ENTREGADO")
                .paymentMethod("TARJETA_PAGADO")
                .build());

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(1);
        assertMoney(row(report, "Canal").getTotalAmount(), "10.00");
        assertUnits(row(report, "Canal").getUnits(), "1");
    }

    @Test
    void draftAndPendingAuthorizationReturnsAreIgnored() throws Exception {
        ProductCategoryEntity cat = category("Borrador");
        ProductEntity product = product("DRF-1", cat);
        LocalDate day = LocalDate.of(2026, 8, 8);
        KioskSaleEntity sale = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("60.00"),
                line(product, "DRF-1", "1", "60.00", "60.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "60.00",
                "DRAFT", day.atTime(10, 0), null);
        headerReturn(kiosk, sale, sale.getItems().get(0), product, "1", "60.00",
                "PENDING_AUTHORIZATION", day.atTime(11, 0), null);

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "Borrador").getUnits(), "1");
        assertMoney(row(report, "Borrador").getTotalAmount(), "60.00");
    }

    @Test
    void felStatusSkippedStillCounts() throws Exception {
        ProductCategoryEntity cat = category("FelSkip");
        LocalDate day = LocalDate.of(2026, 8, 9);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, "SKIPPED", BigDecimal.ZERO, money("22.00"),
                line(product("FEL-1", cat), "FEL-1", "1", "22.00", "22.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertMoney(row(report, "FelSkip").getTotalAmount(), "22.00");
        assertUnits(row(report, "FelSkip").getUnits(), "1");
    }

    @Test
    void evenExchangeLeavesMoneyOnOriginalCategoryAndMovesUnits() throws Exception {
        ProductCategoryEntity original = category("ParA");
        ProductCategoryEntity given = category("ParB");
        ProductEntity productA = product("PAR-A", original);
        ProductEntity productB = product("PAR-B", given);
        LocalDate day = LocalDate.of(2026, 9, 9);
        KioskSaleEntity saleA = sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(productA, "PAR-A", "1", "100.00", "100.00"));
        sale(kiosk, day, day.atTime(10, 0), "COMPLETED", false, null,
                money("100.00"), money("0.00"),
                line(productB, "PAR-B", "1", "100.00", "100.00"));
        headerReturn(kiosk, saleA, saleA.getItems().get(0), productA, "1", "100.00",
                "COMPLETED", day.atTime(11, 0), "EXCHANGE");

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertUnits(row(report, "ParA").getUnits(), "0");
        assertMoney(row(report, "ParA").getTotalAmount(), "100.00");
        assertUnits(row(report, "ParB").getUnits(), "1");
        assertMoney(row(report, "ParB").getTotalAmount(), "0.00");
        assertUnits(report.getTotals().getUnits(), "1");
        assertMoney(report.getTotals().getTotalAmount(), "100.00");
    }

    @Test
    void exchangeAcrossPeriodBoundaryMovesUnitsOnTheSlipDay() throws Exception {
        ProductCategoryEntity original = category("CruceA");
        ProductCategoryEntity given = category("CruceB");
        ProductEntity productA = product("CRU-A", original);
        ProductEntity productB = product("CRU-B", given);
        LocalDate saleDay = LocalDate.of(2026, 9, 1);
        LocalDate exchangeDay = LocalDate.of(2026, 9, 2);
        KioskSaleEntity saleA = sale(kiosk, saleDay, saleDay.atTime(9, 0), "COMPLETED", false, null,
                BigDecimal.ZERO, money("100.00"),
                line(productA, "CRU-A", "1", "100.00", "100.00"));
        sale(kiosk, exchangeDay, exchangeDay.atTime(10, 0), "COMPLETED", false, null,
                money("100.00"), money("50.00"),
                line(productB, "CRU-B", "1", "150.00", "150.00"));
        headerReturn(kiosk, saleA, saleA.getItems().get(0), productA, "1", "100.00",
                "COMPLETED", exchangeDay.atTime(11, 0), "EXCHANGE");

        KioskSalesByCategoryReportResponse first = report(saleDay, saleDay, null);
        assertUnits(row(first, "CruceA").getUnits(), "1");
        assertMoney(row(first, "CruceA").getTotalAmount(), "100.00");
        assertThat(first.getRows()).extracting(Row::getCategoryName).containsExactly("CruceA");

        KioskSalesByCategoryReportResponse second = report(exchangeDay, exchangeDay, null);
        assertUnits(row(second, "CruceA").getUnits(), "-1");
        assertMoney(row(second, "CruceA").getTotalAmount(), "0.00");
        assertUnits(row(second, "CruceB").getUnits(), "1");
        assertMoney(row(second, "CruceB").getTotalAmount(), "50.00");
        assertUnits(second.getTotals().getUnits(), "0");
        assertMoney(second.getTotals().getTotalAmount(), "50.00");
    }

    @Test
    void categoryFollowsCurrentProductCategoryId() throws Exception {
        ProductCategoryEntity oldCat = category("Vieja");
        ProductCategoryEntity newCat = category("Actual");
        ProductEntity product = product("MOV-1", oldCat);
        LocalDate day = LocalDate.of(2026, 10, 1);
        sale(kiosk, day, day.atTime(9, 0), "COMPLETED", false, null, BigDecimal.ZERO, money("33.00"),
                line(product, "MOV-1", "1", "33.00", "33.00"));
        product.setCategoryId(newCat.getId());
        productRepository.saveAndFlush(product);
        entityManager.flush();
        entityManager.clear();

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).extracting(Row::getCategoryName).containsExactly("Actual");
        assertThat(row(report, "Actual").getCategoryId()).isEqualTo(newCat.getId());
        assertMoney(row(report, "Actual").getTotalAmount(), "33.00");
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
        return location(code, "Kiosko " + code, "KIOSKO", pilot);
    }

    private LocationEntity location(String code, String name, String categoria) {
        return location(code, name, categoria, false);
    }

    private LocationEntity location(String code, String name, String categoria, boolean pilot) {
        return locationRepository.saveAndFlush(LocationEntity.builder()
                .code(code)
                .name(name)
                .categoria(categoria)
                .posTestMode(pilot)
                .posMode("STANDARD")
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
        return productRepository.saveAndFlush(ProductEntity.builder()
                .code(code + "-" + SEQ.incrementAndGet())
                .name(code)
                .categoryId(category == null ? null : category.getId())
                .build());
    }

    private KioskSaleItemEntity line(ProductEntity product, String code, String qty, String price, String total) {
        return line(product, code, qty, price, total, null);
    }

    private KioskSaleItemEntity line(
            ProductEntity product,
            String code,
            String qty,
            String price,
            String total,
            String itemDiscount
    ) {
        return KioskSaleItemEntity.builder()
                .productId(product.getId())
                .productCode(code)
                .quantity(new BigDecimal(qty))
                .unitPrice(money(price))
                .lineTotal(money(total))
                .discountAmount(itemDiscount == null ? null : money(itemDiscount))
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

    private static Row row(KioskSalesByCategoryReportResponse report, String name) {
        return report.getRows().stream()
                .filter(row -> name.equals(row.getCategoryName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sin fila " + name + " en " + report.getRows()));
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
