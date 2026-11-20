package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByCategoryReportResponse.Row;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Entrecueros exclusion variants not covered by
 * {@link KioskSalesByCategoryReportH2Test} (exact {@code ENTRECUEROS} / {@code ENTRECUEROS PUEBLITO})
 * or {@link KioskSalesByCategorySubgroupQaTest} (return of a STANDARD sale at exact Entrecueros).
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
@Sql(statements = "ALTER TABLE locations ALTER COLUMN pos_mode SET NULL",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
class KioskSalesEntrecuerosExclusionQaTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;
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

    @MockBean
    private SecurityUtil securityUtil;

    private UserEntity admin;
    private LocationEntity standard;

    @BeforeEach
    void adminUser() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            System.out.println("Test datasource URL: " + url);
            assertThat(url).startsWith("jdbc:h2:mem");
        }
        admin = userWithRole("ADMIN");
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());
        standard = kiosk("KS" + SEQ.incrementAndGet(), "STANDARD", "Kiosko Standard");
    }

    @Test
    void lowercasePosModeExcludesSalesAndReturns() throws Exception {
        LocationEntity excluded = kioskWithPosMode("entrecueros", "Kiosko Lower");
        assertExcludedFromSalesReturnsDefaultAndExplicitIds(excluded);
    }

    @Test
    void paddedPosModeExcludesSalesAndReturns() throws Exception {
        LocationEntity excluded = kioskWithPosMode(" ENTRECUEROS ", "Kiosko Padded");
        assertExcludedFromSalesReturnsDefaultAndExplicitIds(excluded);
    }

    @Test
    void nullPosModeOnNormalNameIsIncludedAsStandard() throws Exception {
        LocationEntity unnamed = kiosk("KN" + SEQ.incrementAndGet(), "STANDARD", "Kiosko Oakland Centro");
        setPosModeInDatabase(unnamed.getId(), null);
        ProductCategoryEntity cat = category("CueroNull");
        ProductEntity product = product("CUO-NULL", cat);
        sale(unnamed, DAY, money("55.00"), line(product, "1", "55.00"));

        KioskSalesByCategoryReportResponse omitted = report(DAY, DAY, null);
        assertMoney(row(omitted, "CueroNull").getTotalAmount(), "55.00");
        assertUnits(row(omitted, "CueroNull").getUnits(), "1");
        assertMoney(omitted.getTotals().getTotalAmount(), "55.00");

        KioskSalesByCategoryReportResponse explicit = report(DAY, DAY, String.valueOf(unnamed.getId()));
        assertMoney(row(explicit, "CueroNull").getTotalAmount(), "55.00");
        assertThat(explicit.getRows()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Kiosko Entrecueros Oakland",
            "ENTRECUEROS",
            "entreCueros"
    })
    void nameRuleExcludesStandardPosModeCaseInsensitively(String name) throws Exception {
        LocationEntity named = kiosk("NM" + SEQ.incrementAndGet(), "STANDARD", name);
        assertExcludedFromSalesReturnsDefaultAndExplicitIds(named);
    }

    @Test
    void defaultPathExcludesAllVariantsAndTotalsEqualOnlyNonEntrecueros() throws Exception {
        LocationEntity lower = kioskWithPosMode("entrecueros", "Kiosko LowerMix");
        LocationEntity padded = kioskWithPosMode(" ENTRECUEROS ", "Kiosko PaddedMix");
        LocationEntity oakland = kiosk("NO" + SEQ.incrementAndGet(), "STANDARD", "Kiosko Entrecueros Oakland");
        LocationEntity mixedName = kiosk("NC" + SEQ.incrementAndGet(), "STANDARD", "entreCueros");
        LocationEntity nullMode = kiosk("NN" + SEQ.incrementAndGet(), "STANDARD", "Kiosko Normal Null");
        setPosModeInDatabase(nullMode.getId(), null);

        ProductCategoryEntity cat = category("CueroMix");
        ProductEntity product = product("CUO-MIX", cat);
        sale(standard, DAY, money("10.00"), line(product, "1", "10.00"));
        sale(nullMode, DAY, money("15.00"), line(product, "1", "15.00"));
        sale(lower, DAY, money("100.00"), line(product, "1", "100.00"));
        sale(padded, DAY, money("200.00"), line(product, "1", "200.00"));
        sale(oakland, DAY, money("300.00"), line(product, "1", "300.00"));
        sale(mixedName, DAY, money("400.00"), line(product, "1", "400.00"));

        KioskSalesByCategoryReportResponse omitted = report(DAY, DAY, null);
        assertThat(omitted.getRows()).hasSize(1);
        assertMoney(row(omitted, "CueroMix").getTotalAmount(), "25.00");
        assertUnits(row(omitted, "CueroMix").getUnits(), "2");
        assertMoney(omitted.getTotals().getTotalAmount(), "25.00");
        assertUnits(omitted.getTotals().getUnits(), "2");
        assertMoney(sumAmounts(omitted), "25.00");
    }

    @Test
    void returnAtLowercaseAndPaddedEntrecuerosDoesNotSubtractStandardSale() throws Exception {
        LocationEntity lower = kioskWithPosMode("entrecueros", "Kiosko LowerRet");
        LocationEntity padded = kioskWithPosMode(" ENTRECUEROS ", "Kiosko PaddedRet");
        ProductCategoryEntity cat = category("CueroRetVar");
        ProductEntity product = product("CUO-RETV", cat);
        KioskSaleEntity standardSale = sale(standard, DAY, money("80.00"), line(product, "1", "80.00"));
        headerReturn(lower, standardSale, standardSale.getItems().get(0), product, "1", "80.00",
                "COMPLETED", DAY.atTime(12, 0), "RETURN");
        headerReturn(padded, standardSale, standardSale.getItems().get(0), product, "1", "80.00",
                "COMPLETED", DAY.atTime(12, 1), "RETURN");

        KioskSalesByCategoryReportResponse omitted = report(DAY, DAY, null);
        assertMoney(row(omitted, "CueroRetVar").getTotalAmount(), "80.00");
        assertUnits(row(omitted, "CueroRetVar").getUnits(), "1");

        KioskSalesByCategoryReportResponse mixed = report(DAY, DAY,
                standard.getId() + "," + lower.getId() + "," + padded.getId());
        assertThat(mixed.getRows()).isNotNull();
        assertMoney(row(mixed, "CueroRetVar").getTotalAmount(), "80.00");
        assertUnits(row(mixed, "CueroRetVar").getUnits(), "1");
    }

    private void assertExcludedFromSalesReturnsDefaultAndExplicitIds(LocationEntity excluded) throws Exception {
        ProductCategoryEntity cat = category("CueroEx" + SEQ.incrementAndGet());
        ProductEntity product = product("CUO-EX" + SEQ.incrementAndGet(), cat);
        KioskSaleEntity standardSale = sale(standard, DAY, money("100.00"), line(product, "1", "100.00"));
        KioskSaleEntity excludedSale = sale(excluded, DAY, money("888.00"), line(product, "1", "888.00"));
        headerReturn(excluded, excludedSale, excludedSale.getItems().get(0), product, "1", "30.00",
                "COMPLETED", DAY.atTime(12, 0), "RETURN");
        headerReturn(excluded, standardSale, standardSale.getItems().get(0), product, "1", "100.00",
                "COMPLETED", DAY.atTime(12, 1), "RETURN");

        KioskSalesByCategoryReportResponse omitted = report(DAY, DAY, null);
        assertMoney(row(omitted, cat.getName()).getTotalAmount(), "100.00");
        assertUnits(row(omitted, cat.getName()).getUnits(), "1");
        assertMoney(omitted.getTotals().getTotalAmount(), "100.00");

        KioskSalesByCategoryReportResponse mixed = report(DAY, DAY, standard.getId() + "," + excluded.getId());
        assertMoney(row(mixed, cat.getName()).getTotalAmount(), "100.00");
        assertUnits(row(mixed, cat.getName()).getUnits(), "1");
        assertMoney(mixed.getTotals().getTotalAmount(), "100.00");

        KioskSalesByCategoryReportResponse onlyExcluded = report(DAY, DAY, String.valueOf(excluded.getId()));
        assertThat(onlyExcluded.getRows()).isEmpty();
        assertMoney(onlyExcluded.getTotals().getTotalAmount(), "0.00");
        assertUnits(onlyExcluded.getTotals().getUnits(), "0");
        assertMoney(onlyExcluded.getTotals().getTotalAmountNoIva(), "0.00");
    }

    private KioskSalesByCategoryReportResponse report(LocalDate from, LocalDate to, String kioskIds)
            throws Exception {
        var request = get("/api/reports/kiosk-sales-by-category")
                .param("from", from.toString())
                .param("to", to.toString());
        if (kioskIds != null) {
            request = request.param("kioskIds", kioskIds);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                KioskSalesByCategoryReportResponse.class);
    }

    private UserEntity userWithRole(String roleName) {
        RoleEntity role = roleRepository.save(RoleEntity.builder().name(roleName + "-" + SEQ.incrementAndGet()).build());
        if ("ADMIN".equals(roleName)) {
            role.setName("ADMIN");
            role = roleRepository.save(role);
        }
        return userRepository.saveAndFlush(UserEntity.builder()
                .username("u" + SEQ.incrementAndGet())
                .email("u" + SEQ.get() + "@t.local")
                .password("x")
                .roles(new HashSet<>(Set.of(role)))
                .build());
    }

    private LocationEntity kioskWithPosMode(String posMode, String name) {
        LocationEntity location = kiosk("EV" + SEQ.incrementAndGet(), "STANDARD", name);
        setPosModeInDatabase(location.getId(), posMode);
        return locationRepository.findById(location.getId()).orElseThrow();
    }

    private LocationEntity kiosk(String code, String posMode, String name) {
        return locationRepository.saveAndFlush(LocationEntity.builder()
                .code(code)
                .name(name)
                .categoria("KIOSKO")
                .posTestMode(false)
                .posMode(posMode)
                .posOpeningCashAmount(new BigDecimal("300.00"))
                .build());
    }

    private void setPosModeInDatabase(Long id, String posMode) {
        entityManager.flush();
        jdbc.update("UPDATE locations SET pos_mode = ? WHERE id = ?", posMode, id);
        entityManager.clear();
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
                .categoryId(category.getId())
                .build());
    }

    private KioskSaleItemEntity line(ProductEntity product, String qty, String amount) {
        return KioskSaleItemEntity.builder()
                .productId(product.getId())
                .productCode(product.getCode())
                .quantity(new BigDecimal(qty))
                .unitPrice(money(amount))
                .lineTotal(money(amount))
                .build();
    }

    private KioskSaleEntity sale(LocationEntity location, LocalDate day, BigDecimal total,
            KioskSaleItemEntity... items) {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .saleNumber("S-" + SEQ.incrementAndGet())
                .kioskLocationId(location.getId())
                .soldByUserId(admin.getId())
                .saleDate(day)
                .soldAt(day.atTime(9, 0))
                .status("COMPLETED")
                .testSale(false)
                .discountAmount(BigDecimal.ZERO)
                .subtotal(total)
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
        return slipRepository.saveAndFlush(KioskExchangeSlipEntity.builder()
                .slipNumber("R-" + SEQ.incrementAndGet())
                .seriesCode("K")
                .slipType(slipType)
                .kioskLocationId(location.getId())
                .originalSaleId(sale.getId())
                .originalSaleItemId(item.getId())
                .returnedProductId(product.getId())
                .returnedQuantity(new BigDecimal(qty))
                .returnedAmount(money(listAmount))
                .status(status)
                .completedAt(completedAt)
                .build());
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
