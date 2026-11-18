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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Report-level subgroup / Entrecueros gaps not covered by
 * {@link KioskSalesByCategoryReportH2Test} or {@link com.fossiles.fossilescorebackend.application.util.KioskSalesCategorySubgroupTest}.
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
class KioskSalesByCategorySubgroupQaTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    private LocationEntity kiosk;

    @BeforeEach
    void adminUser() {
        admin = userWithRole("ADMIN");
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());
        kiosk = kiosk("K" + SEQ.incrementAndGet(), "STANDARD");
    }

    @Test
    void leftoverUnclassifiedWalletAndBeltAttributesStayInSinClasificar() throws Exception {
        ProductCategoryEntity wallets = category("BilleterasNull");
        ProductCategoryEntity belts = category("CinchosHuecos");
        LocalDate day = LocalDate.of(2026, 6, 20);
        sale(kiosk, day, money("14.00"),
                line(product("BILL-NULL", wallets, "Billetera sin audiencia", null, null, false),
                        "1", "14.00"));
        sale(kiosk, day, money("16.00"),
                line(product("BELT-CAB-NULL", belts, "Cincho caballero sin tipo", "CABALLERO", null, false),
                        "1", "16.00"));
        sale(kiosk, day, money("19.00"),
                line(product("BELT-AUD-NULL", belts, "Cincho sin audiencia", null, "CASUAL", false),
                        "1", "19.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        Row wallet = row(report, "BilleterasNull", "SIN_CLASIFICAR");
        assertThat(wallet.getSubgroupLabel()).isEqualTo("Sin clasificar");
        assertMoney(wallet.getTotalAmount(), "14.00");
        assertThat(wallet.getCategoryId()).isEqualTo(wallets.getId());

        Row caballeroNullType = row(report, "CinchosHuecos", "SIN_CLASIFICAR");
        assertThat(caballeroNullType.getSubgroupLabel()).isEqualTo("Sin clasificar");
        assertThat(caballeroNullType.getCategoryId()).isEqualTo(belts.getId());
        assertMoney(caballeroNullType.getTotalAmount(), "35.00");
        assertUnits(caballeroNullType.getUnits(), "2");
    }

    @Test
    void beltWhoseNameContainsBilleteraIsBeltNotWallet() throws Exception {
        ProductCategoryEntity cat = category("Mixtos");
        LocalDate day = LocalDate.of(2026, 6, 21);
        sale(kiosk, day, money("25.00"),
                line(product("BELT-BILL", cat, "Billetera", "DAMA", "CASUAL", false), "1", "25.00"));
        sale(kiosk, day, money("13.00"),
                line(product("BILL-ONLY", cat, "Billetera", "DAMA", null, false), "1", "13.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(2);
        Row belt = row(report, "Mixtos", "DAMA_CASUAL");
        assertThat(belt.getSubgroupLabel()).isEqualTo("Dama casual");
        assertMoney(belt.getTotalAmount(), "25.00");
        Row wallet = row(report, "Mixtos", "DAMA");
        assertThat(wallet.getSubgroupLabel()).isEqualTo("Dama");
        assertMoney(wallet.getTotalAmount(), "13.00");
        assertThat(belt.getCategoryId()).isEqualTo(wallet.getCategoryId()).isEqualTo(cat.getId());
    }

    @Test
    void nonSplitProductKeepsNullSubgroupEvenWithAudience() throws Exception {
        ProductCategoryEntity cat = category("BolsasAud");
        LocalDate day = LocalDate.of(2026, 6, 22);
        sale(kiosk, day, money("21.00"),
                line(product("BOL-D", cat, "Bolso dama", "DAMA", null, false), "1", "21.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(1);
        Row row = row(report, "BolsasAud");
        assertThat(row.getSubgroupCode()).isNull();
        assertThat(row.getSubgroupLabel()).isNull();
        assertThat(row.getCategoryId()).isEqualTo(cat.getId());
        assertMoney(row.getTotalAmount(), "21.00");
    }

    @Test
    void sinClasificarSharesCategoryIdAndSubgroupsSumWithIvaRoundedOnce() throws Exception {
        ProductCategoryEntity cat = category("BilleterasIva");
        LocalDate day = LocalDate.of(2026, 6, 23);
        sale(kiosk, day, money("1.00"),
                line(product("BILL-D", cat, "Billetera dama", "DAMA", null, false), "1", "1.00"));
        sale(kiosk, day, money("1.00"),
                line(product("BILL-C", cat, "Billetera caballero", "CABALLERO", null, false), "1", "1.00"));
        sale(kiosk, day, money("1.00"),
                line(product("BILL-U", cat, "Billetera unisex", "UNISEX", null, false), "1", "1.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).hasSize(3);
        assertThat(report.getRows()).allMatch(row -> cat.getId().equals(row.getCategoryId()));
        assertThat(row(report, "BilleterasIva", "SIN_CLASIFICAR").getCategoryId()).isEqualTo(cat.getId());

        BigDecimal rowNoIva = BigDecimal.ZERO;
        for (Row row : report.getRows()) {
            assertMoney(row.getTotalAmount(), "1.00");
            assertMoney(row.getTotalAmountNoIva(), "0.89");
            rowNoIva = rowNoIva.add(row.getTotalAmountNoIva());
        }
        assertMoney(rowNoIva, "2.67");
        assertUnits(sumUnits(report), "3");
        assertMoney(sumAmounts(report), "3.00");
        assertUnits(report.getTotals().getUnits(), "3");
        assertMoney(report.getTotals().getTotalAmount(), "3.00");
        assertMoney(report.getTotals().getTotalAmountNoIva(), "2.68");
    }

    @Test
    void returnOfDamaCasualBeltReducesThatRowAndKeepsNegative() throws Exception {
        ProductCategoryEntity cat = category("CinchosRet");
        ProductEntity damaCasual = product("BELT-DC", cat, "Cincho dama casual", "DAMA", "CASUAL", false);
        LocalDate saleDay = LocalDate.of(2026, 6, 1);
        LocalDate returnDay = LocalDate.of(2026, 6, 24);
        KioskSaleEntity sale = sale(kiosk, saleDay, money("40.00"),
                line(damaCasual, "1", "40.00"));
        sale(kiosk, returnDay, money("12.00"),
                line(product("BELT-CC", cat, "Cincho caballero casual", "CABALLERO", "CASUAL", false),
                        "1", "12.00"));
        headerReturn(kiosk, sale, sale.getItems().get(0), damaCasual, "1", "40.00",
                "COMPLETED", returnDay.atTime(11, 0), null);

        KioskSalesByCategoryReportResponse report = report(returnDay, returnDay, null);
        Row negative = row(report, "CinchosRet", "DAMA_CASUAL");
        assertThat(negative.getSubgroupLabel()).isEqualTo("Dama casual");
        assertUnits(negative.getUnits(), "-1");
        assertMoney(negative.getTotalAmount(), "-40.00");
        assertThat(negative.getCategoryId()).isEqualTo(cat.getId());
        Row caballero = row(report, "CinchosRet", "CABALLERO_CASUAL");
        assertMoney(caballero.getTotalAmount(), "12.00");
        assertThat(report.getRows()).extracting(Row::getSubgroupCode)
                .containsExactly("CABALLERO_CASUAL", "DAMA_CASUAL");
        assertUnits(report.getTotals().getUnits(), "0");
        assertMoney(report.getTotals().getTotalAmount(), "-28.00");
    }

    @Test
    void exchangeMovesUnitsOfTheReturnedSubgroup() throws Exception {
        ProductCategoryEntity cat = category("CinchosCambio");
        ProductEntity damaCasual = product("EX-DC", cat, "Cincho dama casual", "DAMA", "CASUAL", false);
        ProductEntity caballeroCasual = product("EX-CC", cat, "Cincho caballero casual", "CABALLERO", "CASUAL", false);
        LocalDate day = LocalDate.of(2026, 6, 25);
        KioskSaleEntity original = sale(kiosk, day, money("100.00"),
                line(damaCasual, "1", "100.00"));
        sale(kiosk, day, money("0.00"), money("100.00"),
                line(caballeroCasual, "1", "100.00"));
        headerReturn(kiosk, original, original.getItems().get(0), damaCasual, "1", "100.00",
                "COMPLETED", day.atTime(11, 0), "EXCHANGE");

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        Row returned = row(report, "CinchosCambio", "DAMA_CASUAL");
        assertUnits(returned.getUnits(), "0");
        assertMoney(returned.getTotalAmount(), "100.00");
        Row given = row(report, "CinchosCambio", "CABALLERO_CASUAL");
        assertUnits(given.getUnits(), "1");
        assertMoney(given.getTotalAmount(), "0.00");
        assertUnits(report.getTotals().getUnits(), "1");
        assertMoney(report.getTotals().getTotalAmount(), "100.00");
    }

    @Test
    void sameCategorySubgroupsAreSeparateRowsSortedAmongAllRows() throws Exception {
        ProductCategoryEntity belts = category("CinchosOrd");
        ProductCategoryEntity bags = category("BolsasOrd");
        LocalDate day = LocalDate.of(2026, 6, 26);
        sale(kiosk, day, money("80.00"),
                line(product("ORD-DC", belts, "Cincho dama casual", "DAMA", "CASUAL", false), "1", "80.00"));
        sale(kiosk, day, money("20.00"),
                line(product("ORD-CC", belts, "Cincho caballero casual", "CABALLERO", "CASUAL", false),
                        "1", "20.00"));
        sale(kiosk, day, money("50.00"),
                line(product("ORD-BOL", bags, "Bolso", null, null, false), "1", "50.00"));

        KioskSalesByCategoryReportResponse report = report(day, day, null);
        assertThat(report.getRows()).extracting(Row::getCategoryName, Row::getSubgroupCode)
                .containsExactly(
                        tuple("CinchosOrd", "DAMA_CASUAL"),
                        tuple("BolsasOrd", null),
                        tuple("CinchosOrd", "CABALLERO_CASUAL"));
        assertThat(row(report, "BolsasOrd").getSubgroupLabel()).isNull();
        assertMoney(sumAmounts(report), "150.00");
        assertMoney(report.getTotals().getTotalAmount(), "150.00");
    }

    @Test
    void entrecuerosReturnOfStandardSaleIsExcluded() throws Exception {
        LocationEntity entrecueros = kiosk("ETRC" + SEQ.incrementAndGet(), "ENTRECUEROS");
        ProductCategoryEntity cat = category("CueroRet");
        ProductEntity product = product("CUO-STD", cat, "Bolso", null, null, false);
        LocalDate day = LocalDate.of(2026, 6, 27);
        KioskSaleEntity standardSale = sale(kiosk, day, money("80.00"),
                line(product, "1", "80.00"));
        headerReturn(entrecueros, standardSale, standardSale.getItems().get(0), product, "1", "80.00",
                "COMPLETED", day.atTime(12, 0), null);

        KioskSalesByCategoryReportResponse omitted = report(day, day, null);
        assertThat(omitted.getRows()).hasSize(1);
        assertMoney(row(omitted, "CueroRet").getTotalAmount(), "80.00");
        assertUnits(row(omitted, "CueroRet").getUnits(), "1");

        KioskSalesByCategoryReportResponse mixed = report(day, day,
                kiosk.getId() + "," + entrecueros.getId());
        assertMoney(row(mixed, "CueroRet").getTotalAmount(), "80.00");
        assertUnits(row(mixed, "CueroRet").getUnits(), "1");
    }

    @Test
    void unknownKioskIdStill400WhenListedWithEntrecueros() throws Exception {
        LocationEntity entrecueros = kiosk("ETRC" + SEQ.incrementAndGet(), "ENTRECUEROS");
        mockMvc.perform(get("/api/reports/kiosk-sales-by-category")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-01")
                        .param("kioskIds", entrecueros.getId() + ",999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El kiosko seleccionado no existe o no es un kiosko."));
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
        UserEntity user = UserEntity.builder()
                .username("u" + SEQ.incrementAndGet())
                .email("u" + SEQ.get() + "@t.local")
                .password("x")
                .roles(new HashSet<>(Set.of(role)))
                .build();
        return userRepository.saveAndFlush(user);
    }

    private LocationEntity kiosk(String code, String posMode) {
        return locationRepository.saveAndFlush(LocationEntity.builder()
                .code(code)
                .name("Kiosko " + code)
                .categoria("KIOSKO")
                .posTestMode(false)
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
        return sale(location, day, total, BigDecimal.ZERO, items);
    }

    private KioskSaleEntity sale(
            LocationEntity location,
            LocalDate day,
            BigDecimal total,
            BigDecimal discount,
            KioskSaleItemEntity... items
    ) {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .saleNumber("S-" + SEQ.incrementAndGet())
                .kioskLocationId(location.getId())
                .soldByUserId(admin.getId())
                .saleDate(day)
                .soldAt(day.atTime(9, 0))
                .status("COMPLETED")
                .testSale(false)
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
        return slipRepository.saveAndFlush(KioskExchangeSlipEntity.builder()
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
                .build());
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

    private static BigDecimal sumUnits(KioskSalesByCategoryReportResponse report) {
        return report.getRows().stream()
                .map(Row::getUnits)
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
