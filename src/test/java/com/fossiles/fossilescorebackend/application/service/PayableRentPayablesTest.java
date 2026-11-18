package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayableRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesSummary;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.RentGenerationResult;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import com.fossiles.fossilescorebackend.infrastructure.controller.SupplierPaymentController;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialReceiptEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BankAccountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialReceiptRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OtherRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayableDocumentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PaymentMethodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentAllocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Rentas por pagar contra H2: generación (kiosco y otra renta), corrección de vencimiento, alcance de la lista y
 * el rechazo de un pago que mezcla rentas con proveedores.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payable_rents;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "sentry.enabled=false",
        "sentry.dsn="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PayableRentPayablesTest.Cfg.class)
class PayableRentPayablesTest {

    private static final String MIXED_PAYMENT = "Un pago no puede combinar rentas con pagos a proveedores.";
    private static final String BAD_SCOPE = "El alcance debe ser SUPPLIERS o RENTS.";
    private static final String H2_URL =
            "jdbc:h2:mem:payable_rents;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void h2Only(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> H2_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("sentry.enabled", () -> "false");
        registry.add("sentry.dsn", () -> "");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = PayableDocumentRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    PayableDocumentRepository.class, SupplierPaymentAllocationRepository.class, SupplierRepository.class,
                    PurchaseOrderRepository.class, MaterialReceiptRepository.class, SupplierPaymentRepository.class,
                    PaymentMethodRepository.class, BankAccountRepository.class, KioskRentRepository.class,
                    LocationRepository.class, KioskFixedCostRepository.class, KioskSiteRepository.class,
                    OtherRentRepository.class}))
    @Import({PayableDocumentService.class, SupplierPaymentService.class, KioskRentService.class})
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(
                    PayableDocumentEntity.class.getName(),
                    SupplierPaymentAllocationEntity.class.getName(),
                    SupplierEntity.class.getName(), PurchaseOrderEntity.class.getName(), MaterialReceiptEntity.class.getName(),
                    SupplierPaymentEntity.class.getName(), PaymentMethodEntity.class.getName(), BankAccountEntity.class.getName(),
                    KioskRentEntity.class.getName(), LocationEntity.class.getName(), UserEntity.class.getName(),
                    DepartmentEntity.class.getName(), CostCenterEntity.class.getName(), OperationalUnitEntity.class.getName(),
                    RoleEntity.class.getName(), PermissionEntity.class.getName(), KioskFixedCostEntity.class.getName(),
                    KioskSiteEntity.class.getName(), OtherRentEntity.class.getName());
        }
    }

    @MockitoBean SupplierPaymentAccessGuard guard;
    @MockitoBean AccountingService accountingService;
    @MockitoBean SupplierPaymentConfigService configService;
    @MockitoBean KioskRentAccessGuard rentGuard;

    @Autowired DataSource dataSource;
    @Autowired PayableDocumentService documents;
    @Autowired SupplierPaymentService payments;
    @Autowired PayableDocumentRepository documentRepository;
    @Autowired OtherRentRepository otherRentRepository;
    @Autowired KioskRentRepository rentRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired KioskSiteRepository siteRepository;
    @Autowired KioskFixedCostRepository fixedCostRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired PurchaseOrderRepository orderRepository;
    @Autowired PaymentMethodRepository methodRepository;
    @Autowired SupplierPaymentRepository paymentRepository;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            System.out.println("Test datasource URL: " + url);
            assertTrue(url.startsWith("jdbc:h2:mem"), url);
        }
        lenient().when(guard.assertCanPay()).thenReturn(7L);
        lenient().when(guard.canPay()).thenReturn(true);
        lenient().when(configService.payablesStartDate()).thenReturn(LocalDate.of(2000, 1, 1));
    }

    @Test
    void generateRentsCreatesKioskAndOtherRentWithClampedDueDate() throws Exception {
        SupplierEntity landlord = supplier("Inmobiliaria Sol");
        LocationEntity february = kiosk("KF", "Kiosco Febrero", "Inmobiliaria Sol", 31, new BigDecimal("1500.00"));
        LocationEntity fifteenth = kiosk("KQ", "Kiosco Quince", "Otro Arrendador", 15, new BigDecimal("900.00"));
        kiosk("KS", "Kiosco Sin Renta", "Sin Finanzas", 10, null);
        OtherRentEntity bodega = other("Bodega Norte", "Don Pedro", new BigDecimal("800.00"), 31, null, null, true);
        OtherRentEntity office = other("Oficina propia", null, new BigDecimal("400.00"), 15, null, null, true);
        other("Contrato corto", "Ana", new BigDecimal("100.00"), 31, null, LocalDate.of(2026, 2, 1), true);
        other("Sin dia", "Ana", new BigDecimal("100.00"), null, null, null, true);
        other("Sin monto", "Ana", BigDecimal.ZERO, 4, null, null, true);
        other("Cerrada", "Ana", new BigDecimal("999.00"), 1, null, null, false);

        RentGenerationResult result = documents.generateRents("2026-02");

        assertEquals("2026-02", result.period());
        assertEquals(4, result.created());
        assertEquals(0, result.alreadyExisting());
        assertEquals(0, result.updated());
        money("3600.00", result.createdTotal());
        assertEquals(List.of(
                "Kiosco Sin Renta: Finanzas no tiene la renta.",
                "Contrato corto: el contrato no cubre el 2026-02-28.",
                "Sin dia: faltan el arrendador o el día de pago.",
                "Sin monto: Finanzas no tiene la renta."
        ), result.skipped());

        List<PayableDocumentEntity> rows = activeRents("2026-02");
        assertEquals(4, rows.size());
        PayableDocumentEntity kioskRent = byLocation(rows, february.getId());
        assertEquals("RENTA-KF-2026-02", kioskRent.getDocumentNumber());
        assertEquals("Renta de Kiosco Febrero · 2026-02", kioskRent.getConcept());
        assertEquals(LocalDate.of(2026, 2, 28), kioskRent.getDueDate());
        assertEquals(kioskRent.getDueDate(), kioskRent.getIssueDate());
        assertEquals(landlord.getId(), kioskRent.getSupplierId());
        assertEquals("Inmobiliaria Sol", kioskRent.getPayeeName());
        assertNull(kioskRent.getOtherRentId());
        money("1500.00", kioskRent.getAmount());

        PayableDocumentEntity fifteenthDoc = byLocation(rows, fifteenth.getId());
        assertEquals("RENTA-KQ-2026-02", fifteenthDoc.getDocumentNumber());
        assertEquals(LocalDate.of(2026, 2, 15), fifteenthDoc.getDueDate());
        assertEquals("Otro Arrendador", fifteenthDoc.getPayeeName());
        assertNull(fifteenthDoc.getSupplierId());

        PayableDocumentEntity bodegaDoc = byOther(rows, bodega.getId());
        assertEquals("RENTA-O" + bodega.getId() + "-2026-02", bodegaDoc.getDocumentNumber());
        assertEquals("Renta de Bodega Norte · 2026-02", bodegaDoc.getConcept());
        assertEquals(LocalDate.of(2026, 2, 28), bodegaDoc.getDueDate());
        assertEquals(bodegaDoc.getDueDate(), bodegaDoc.getIssueDate());
        assertEquals("Don Pedro", bodegaDoc.getPayeeName());
        assertNull(bodegaDoc.getSupplierId());
        assertNull(bodegaDoc.getLocationId());
        money("800.00", bodegaDoc.getAmount());

        PayableDocumentEntity officeDoc = byOther(rows, office.getId());
        assertEquals("Oficina propia", officeDoc.getPayeeName());
        assertEquals(LocalDate.of(2026, 2, 15), officeDoc.getDueDate());
        assertNull(officeDoc.getLocationId());
        assertNull(officeDoc.getSupplierId());
    }

    @Test
    void regenerateUpdatesUnpaidDueDateAndDoesNotDuplicateOrTouchAllocatedRows() throws Exception {
        LocationEntity kiosk = kiosk("KU", "Kiosco Uno", "Ana", 5, new BigDecimal("100.00"));
        OtherRentEntity bodega = other("Bodega Uno", "Luis", new BigDecimal("50.00"), 5, null, null, true);

        RentGenerationResult first = documents.generateRents("2026-02");
        assertEquals(2, first.created());
        assertEquals(0, first.updated());
        money("150.00", first.createdTotal());
        assertEquals(LocalDate.of(2026, 2, 5), byLocation(activeRents("2026-02"), kiosk.getId()).getDueDate());
        assertEquals(LocalDate.of(2026, 2, 5), byOther(activeRents("2026-02"), bodega.getId()).getDueDate());

        RentGenerationResult again = documents.generateRents("2026-02");
        assertEquals(0, again.created());
        assertEquals(2, again.alreadyExisting());
        assertEquals(0, again.updated());
        assertEquals(2, activeRents("2026-02").size());
        assertEquals(1, activeRents("2026-02").stream().filter(row -> row.getOtherRentId() != null).count());

        setPaymentDay(kiosk.getId(), bodega.getId(), 31);
        RentGenerationResult updated = documents.generateRents("2026-02");
        assertEquals(0, updated.created());
        assertEquals(0, updated.alreadyExisting());
        assertEquals(2, updated.updated());
        money("0", updated.createdTotal());
        PayableDocumentEntity kioskDoc = byLocation(activeRents("2026-02"), kiosk.getId());
        PayableDocumentEntity otherDoc = byOther(activeRents("2026-02"), bodega.getId());
        assertEquals(LocalDate.of(2026, 2, 28), kioskDoc.getDueDate());
        assertEquals(kioskDoc.getDueDate(), kioskDoc.getIssueDate());
        assertEquals(LocalDate.of(2026, 2, 28), otherDoc.getDueDate());
        assertEquals(otherDoc.getDueDate(), otherDoc.getIssueDate());
        money("100.00", kioskDoc.getAmount());
        money("50.00", otherDoc.getAmount());

        Long methodId = method().getId();
        payments.createPayment(pay(methodId, null, kioskDoc.getId(), "10.00"));
        setPaymentDay(kiosk.getId(), bodega.getId(), 10);
        RentGenerationResult afterPayment = documents.generateRents("2026-02");
        assertEquals(0, afterPayment.created());
        assertEquals(1, afterPayment.alreadyExisting());
        assertEquals(1, afterPayment.updated());
        assertEquals(2, activeRents("2026-02").size());
        PayableDocumentEntity locked = byLocation(activeRents("2026-02"), kiosk.getId());
        PayableDocumentEntity moved = byOther(activeRents("2026-02"), bodega.getId());
        assertEquals(LocalDate.of(2026, 2, 28), locked.getDueDate());
        assertEquals(LocalDate.of(2026, 2, 28), locked.getIssueDate());
        assertEquals(LocalDate.of(2026, 2, 10), moved.getDueDate());
        assertEquals(LocalDate.of(2026, 2, 10), moved.getIssueDate());
    }

    @Test
    void payablesScopeFiltersRowsAndRecomputesSummary() throws Exception {
        LocalDate today = GuatemalaDateTime.today();
        SupplierEntity supplier = supplier("Cuero SA");
        PurchaseOrderEntity order = orderRepository.save(PurchaseOrderEntity.builder()
                .code("OC-1").supplierId(supplier.getId()).orderDate(today.minusDays(10))
                .status("RECIBIDA").total(new BigDecimal("100.00")).build());
        documentRepository.save(PayableDocumentEntity.builder()
                .payeeName("Taller Lopez").concept("Flete").issueDate(today.minusDays(10))
                .dueDate(today.minusDays(1)).amount(new BigDecimal("40.00"))
                .kind(PayableDocumentEntity.KIND_MANUAL).build());
        documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier.getId()).payeeName(supplier.getName()).concept("Saldo")
                .issueDate(today).dueDate(today.plusDays(3)).amount(new BigDecimal("10.00"))
                .kind(PayableDocumentEntity.KIND_OPENING).build());
        PayableDocumentEntity rent = documentRepository.save(PayableDocumentEntity.builder()
                .payeeName("Arrendador Libre").concept("Renta").issueDate(today.plusDays(40))
                .dueDate(today.plusDays(40)).amount(new BigDecimal("500.00"))
                .kind(PayableDocumentEntity.KIND_RENT).otherRentId(1L).period("2026-02").build());
        Long methodId = method().getId();
        paymentRepository.save(SupplierPaymentEntity.builder()
                .paymentMethodId(methodId).payDate(today).amount(new BigDecimal("999.00"))
                .payeeName("NADIE").payeeKey("N:nadie").status(SupplierPaymentEntity.STATUS_VOID).build());
        payments.createPayment(pay(methodId, order.getId(), null, "25.00"));
        PaymentRow rentPayment = payments.createPayment(pay(methodId, null, rent.getId(), "100.00"));
        assertTrue(rentPayment.rentPayment());

        PayablesResponse all = payments.payables();
        PayablesResponse blank = payments.payables("  ");
        PayablesResponse suppliers = new SupplierPaymentController(payments, null).payables("SUPPLIERS").getBody();
        PayablesResponse rents = payments.payables("RENTS");

        assertEquals(today, all.today());
        assertTrue(all.canPay());
        assertEquals(4, all.rows().size());
        assertEquals(List.of("DOCUMENTO", "ORDEN", "RENTA", "SALDO_INICIAL"),
                all.rows().stream().map(PayableRow::kind).sorted().toList());
        assertSummary(all.summary(), "525.00", 1, 2, "40.00", 1, 1, "10.00", 1, "125.00", 2);
        assertSummary(blank.summary(), "525.00", 1, 2, "40.00", 1, 1, "10.00", 1, "125.00", 2);

        assertEquals(3, suppliers.rows().size());
        assertTrue(suppliers.rows().stream().noneMatch(row -> "RENTA".equals(row.kind())));
        assertSummary(suppliers.summary(), "125.00", 1, 2, "40.00", 1, 1, "10.00", 1, "25.00", 1);

        assertEquals(1, rents.rows().size());
        assertEquals("RENTA", rents.rows().get(0).kind());
        assertSummary(rents.summary(), "400.00", 0, 0, "0.00", 0, 0, "0.00", 0, "100.00", 1);

        BusinessException invalid = assertThrows(BusinessException.class, () -> payments.payables("rentas"));
        ResponseEntity<Map<String, Object>> http = new GlobalExceptionHandler().handleBusinessException(invalid);
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatusCode());
        assertEquals(BAD_SCOPE, http.getBody().get("message"));
    }

    @Test
    void mixedPaymentIsRejectedAndRentPaymentIsFlagged() throws Exception {
        LocalDate today = GuatemalaDateTime.today();
        SupplierEntity supplier = supplier("Cuero SA");
        PurchaseOrderEntity order = orderRepository.save(PurchaseOrderEntity.builder()
                .code("OC-2").supplierId(supplier.getId()).orderDate(today)
                .status("RECIBIDA").total(new BigDecimal("80.00")).build());
        PayableDocumentEntity rent = documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier.getId()).payeeName(supplier.getName()).concept("Renta kiosco")
                .documentNumber("RENTA-A").issueDate(today).dueDate(today).amount(new BigDecimal("50.00"))
                .kind(PayableDocumentEntity.KIND_RENT).locationId(9L).period("2026-02").build());
        PayableDocumentEntity otherRent = documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier.getId()).payeeName(supplier.getName()).concept("Renta bodega")
                .documentNumber("RENTA-B").issueDate(today).dueDate(today).amount(new BigDecimal("30.00"))
                .kind(PayableDocumentEntity.KIND_RENT).otherRentId(4L).period("2026-02").build());
        PayableDocumentEntity manual = documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier.getId()).payeeName(supplier.getName()).concept("Servicio")
                .issueDate(today).dueDate(today).amount(new BigDecimal("20.00"))
                .kind(PayableDocumentEntity.KIND_MANUAL).build());
        PayableDocumentEntity landlordRent = documentRepository.save(PayableDocumentEntity.builder()
                .payeeName("Otro Dueno").concept("Renta ajena").issueDate(today).dueDate(today)
                .amount(new BigDecimal("15.00")).kind(PayableDocumentEntity.KIND_RENT).locationId(8L).period("2026-02").build());
        Long methodId = method().getId();

        assertRejected(pay(methodId, order.getId(), rent.getId(), "10.00", "10.00"));
        assertRejected(payBothDocuments(methodId, manual.getId(), otherRent.getId(), "10.00", "10.00"));
        assertRejected(pay(methodId, order.getId(), landlordRent.getId(), "10.00", "10.00"));
        assertEquals(0, paymentRepository.count());

        PaymentRow supplierPayment = payments.createPayment(pay(methodId, order.getId(), null, "80.00"));
        assertFalse(supplierPayment.rentPayment());
        PaymentRow rents = payments.createPayment(payBothDocuments(methodId, rent.getId(), otherRent.getId(), "50.00", "30.00"));
        assertTrue(rents.rentPayment());
        money("80.00", rents.amount());
        assertEquals(2, paymentRepository.count());
    }

    private void assertRejected(SupplierPaymentService.CreateRequest request) {
        BusinessException ex = assertThrows(BusinessException.class, () -> payments.createPayment(request));
        ResponseEntity<Map<String, Object>> http = new GlobalExceptionHandler().handleBusinessException(ex);
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatusCode());
        assertEquals(MIXED_PAYMENT, ex.getMessage());
        assertEquals(MIXED_PAYMENT, http.getBody().get("message"));
    }

    private void assertSummary(PayablesSummary summary, String total, int orders, int suppliers, String overdue,
                               int overdueCount, int dueSoon, String in7, int in7Count, String paid, int payments) {
        money(total, summary.totalBalance());
        assertEquals(orders, summary.orderCount());
        assertEquals(suppliers, summary.supplierCount());
        money(overdue, summary.overdueAmount());
        assertEquals(overdueCount, summary.overdueCount());
        assertEquals(dueSoon, summary.dueSoonCount());
        money(in7, summary.dueIn7DaysAmount());
        assertEquals(in7Count, summary.dueIn7DaysCount());
        money(paid, summary.paidThisMonth());
        assertEquals(payments, summary.paymentsThisMonth());
    }

    private void setPaymentDay(Long locationId, Long otherRentId, int day) {
        KioskRentEntity rent = rentRepository.findById(locationId).orElseThrow();
        rent.setPaymentDay(day);
        rentRepository.save(rent);
        OtherRentEntity other = otherRentRepository.findById(otherRentId).orElseThrow();
        other.setPaymentDay(day);
        otherRentRepository.save(other);
    }

    private LocationEntity kiosk(String code, String name, String landlord, int day, BigDecimal financeAmount) {
        LocationEntity location = locationRepository.save(LocationEntity.builder()
                .code(code).name(name).categoria("KIOSKO").posTestMode(false)
                .posMode("STANDARD").posOpeningCashAmount(new BigDecimal("300.00")).build());
        KioskSiteEntity site = siteRepository.save(KioskSiteEntity.builder()
                .name(name).locationId(location.getId()).build());
        if (financeAmount != null) {
            fixedCostRepository.save(KioskFixedCostEntity.builder()
                    .siteId(site.getId()).periodYear(2026).periodMonth(1)
                    .categoryCode("ALQUILER").amount(financeAmount).build());
        }
        rentRepository.save(KioskRentEntity.builder()
                .locationId(location.getId()).landlordName(landlord).paymentDay(day)
                .contractStart(LocalDate.of(2020, 1, 1)).contractEnd(LocalDate.of(2027, 12, 31)).build());
        return location;
    }

    private OtherRentEntity other(String name, String landlord, BigDecimal amount, Integer day,
                                  LocalDate start, LocalDate end, boolean active) {
        return otherRentRepository.save(OtherRentEntity.builder()
                .name(name).category(OtherRentEntity.CATEGORY_BODEGA).landlordName(landlord)
                .monthlyAmount(amount).paymentDay(day).contractStart(start).contractEnd(end).active(active).build());
    }

    private SupplierEntity supplier(String name) {
        return supplierRepository.save(SupplierEntity.builder().name(name).creditDays(30).build());
    }

    private PaymentMethodEntity method() {
        return methodRepository.save(PaymentMethodEntity.builder()
                .name("Transferencia").requiresBank(false).requiresReference(false)
                .printsCheque(false).active(true).sortOrder(1).build());
    }

    private static SupplierPaymentService.CreateRequest pay(Long methodId, Long orderId, Long documentId, String amount) {
        return new SupplierPaymentService.CreateRequest(methodId, null, GuatemalaDateTime.today(), null, null, null,
                List.of(new SupplierPaymentService.AllocationInput(orderId, documentId, new BigDecimal(amount))));
    }

    private static SupplierPaymentService.CreateRequest pay(Long methodId, Long orderId, Long documentId,
                                                            String orderAmount, String documentAmount) {
        return new SupplierPaymentService.CreateRequest(methodId, null, GuatemalaDateTime.today(), null, null, null, List.of(
                new SupplierPaymentService.AllocationInput(orderId, null, new BigDecimal(orderAmount)),
                new SupplierPaymentService.AllocationInput(null, documentId, new BigDecimal(documentAmount))));
    }

    private static SupplierPaymentService.CreateRequest payBothDocuments(Long methodId, Long first, Long second,
                                                                         String firstAmount, String secondAmount) {
        return new SupplierPaymentService.CreateRequest(methodId, null, GuatemalaDateTime.today(), null, null, null, List.of(
                new SupplierPaymentService.AllocationInput(null, first, new BigDecimal(firstAmount)),
                new SupplierPaymentService.AllocationInput(null, second, new BigDecimal(secondAmount))));
    }

    private List<PayableDocumentEntity> activeRents(String period) {
        return documentRepository.findByKindAndPeriodAndStatus(
                PayableDocumentEntity.KIND_RENT, period, PayableDocumentEntity.STATUS_ACTIVE);
    }

    private static PayableDocumentEntity byLocation(List<PayableDocumentEntity> rows, Long locationId) {
        return rows.stream().filter(row -> locationId.equals(row.getLocationId())).findFirst().orElseThrow();
    }

    private static PayableDocumentEntity byOther(List<PayableDocumentEntity> rows, Long otherRentId) {
        return rows.stream().filter(row -> otherRentId.equals(row.getOtherRentId())).findFirst().orElseThrow();
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), actual == null ? "null" : actual.toPlainString());
    }
}
