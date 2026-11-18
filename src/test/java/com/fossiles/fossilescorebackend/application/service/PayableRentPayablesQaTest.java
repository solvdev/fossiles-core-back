package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.RentGenerationResult;
import com.fossiles.fossilescorebackend.infrastructure.controller.SupplierPaymentController;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OtherRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayableDocumentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PaymentMethodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Gaps left by {@link PayableRentPayablesTest}: leap/30-day clamping on generate-rents, non-ACTIVO
 * rows after a payment-day change, contract start, and {@code rentPayment} on payment history.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payable_rents_qa;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
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
class PayableRentPayablesQaTest {

    private static final String H2_URL =
            "jdbc:h2:mem:payable_rents_qa;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void h2Only(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> H2_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("sentry.enabled", () -> "false");
        registry.add("sentry.dsn", () -> "");
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
        lenient().doNothing().when(guard).assertCanView();
        lenient().when(configService.payablesStartDate()).thenReturn(LocalDate.of(2000, 1, 1));
    }

    @Test
    void generateRentsClampsPaymentDay31InLeapFebruaryAndThirtyDayMonths() throws Exception {
        LocationEntity kiosk = kiosk("KL", "Kiosco Largo", "Ana", 31, new BigDecimal("100.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2029, 12, 31));
        OtherRentEntity bodega = other("Bodega Larga", "Luis", new BigDecimal("50.00"), 31, null, null, true);

        RentGenerationResult leap = documents.generateRents("2028-02");
        assertEquals(2, leap.created());
        PayableDocumentEntity leapKiosk = byLocation(activeRents("2028-02"), kiosk.getId());
        PayableDocumentEntity leapOther = byOther(activeRents("2028-02"), bodega.getId());
        assertEquals(LocalDate.of(2028, 2, 29), leapKiosk.getDueDate());
        assertEquals(leapKiosk.getDueDate(), leapKiosk.getIssueDate());
        assertNull(leapKiosk.getOtherRentId());
        assertEquals(LocalDate.of(2028, 2, 29), leapOther.getDueDate());
        assertEquals(leapOther.getDueDate(), leapOther.getIssueDate());
        assertNull(leapOther.getLocationId());

        RentGenerationResult april = documents.generateRents("2026-04");
        assertEquals(2, april.created());
        PayableDocumentEntity aprilKiosk = byLocation(activeRents("2026-04"), kiosk.getId());
        PayableDocumentEntity aprilOther = byOther(activeRents("2026-04"), bodega.getId());
        assertEquals(LocalDate.of(2026, 4, 30), aprilKiosk.getDueDate());
        assertEquals(aprilKiosk.getDueDate(), aprilKiosk.getIssueDate());
        assertEquals(LocalDate.of(2026, 4, 30), aprilOther.getDueDate());
        assertEquals(aprilOther.getDueDate(), aprilOther.getIssueDate());
    }

    @Test
    void regenerateLeavesVoidedRentAloneAndCreatesAReplacement() throws Exception {
        LocationEntity kiosk = kiosk("KV", "Kiosco Vigente", "Ana", 5, new BigDecimal("100.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
        OtherRentEntity bodega = other("Bodega Anulable", "Luis", new BigDecimal("50.00"), 5, null, null, true);

        RentGenerationResult first = documents.generateRents("2026-04");
        assertEquals(2, first.created());
        PayableDocumentEntity kioskDoc = byLocation(activeRents("2026-04"), kiosk.getId());
        PayableDocumentEntity otherDoc = byOther(activeRents("2026-04"), bodega.getId());
        otherDoc.setStatus(PayableDocumentEntity.STATUS_VOID);
        otherDoc.setVoidReason("QA");
        documentRepository.save(otherDoc);

        setPaymentDay(kiosk.getId(), bodega.getId(), 20);
        RentGenerationResult second = documents.generateRents("2026-04");
        assertEquals(1, second.created());
        assertEquals(0, second.alreadyExisting());
        assertEquals(1, second.updated());
        assertEquals(2, activeRents("2026-04").size());

        PayableDocumentEntity stillVoid = documentRepository.findById(otherDoc.getId()).orElseThrow();
        assertEquals(PayableDocumentEntity.STATUS_VOID, stillVoid.getStatus());
        assertEquals(LocalDate.of(2026, 4, 5), stillVoid.getDueDate());
        assertEquals(LocalDate.of(2026, 4, 5), stillVoid.getIssueDate());

        PayableDocumentEntity movedKiosk = byLocation(activeRents("2026-04"), kiosk.getId());
        assertEquals(kioskDoc.getId(), movedKiosk.getId());
        assertEquals(LocalDate.of(2026, 4, 20), movedKiosk.getDueDate());
        assertEquals(movedKiosk.getDueDate(), movedKiosk.getIssueDate());

        PayableDocumentEntity replacement = byOther(activeRents("2026-04"), bodega.getId());
        assertFalse(replacement.getId().equals(otherDoc.getId()));
        assertEquals(LocalDate.of(2026, 4, 20), replacement.getDueDate());
        assertEquals(replacement.getDueDate(), replacement.getIssueDate());
        assertNull(replacement.getLocationId());
    }

    @Test
    void rentsBeforeContractStartAreNotGenerated() throws Exception {
        kiosk("KF", "Kiosco Futuro", "Ana", 15, new BigDecimal("100.00"),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 12, 31));
        other("Bodega futura", "Luis", new BigDecimal("50.00"), 15,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 12, 31), true);

        RentGenerationResult result = documents.generateRents("2026-02");

        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        assertTrue(activeRents("2026-02").isEmpty());
        assertEquals(List.of(
                "Kiosco Futuro: el contrato no cubre el 2026-02-15.",
                "Bodega futura: el contrato no cubre el 2026-02-15."
        ), result.skipped());
    }

    @Test
    void listPaymentsFlagsRentPaymentOnHistoryRows() throws Exception {
        LocalDate today = GuatemalaDateTime.today();
        SupplierEntity supplier = supplier("Cuero SA");
        PurchaseOrderEntity order = orderRepository.save(PurchaseOrderEntity.builder()
                .code("OC-QA").supplierId(supplier.getId()).orderDate(today)
                .status("RECIBIDA").total(new BigDecimal("80.00")).build());
        PayableDocumentEntity rent = documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier.getId()).payeeName(supplier.getName()).concept("Renta")
                .documentNumber("RENTA-QA").issueDate(today).dueDate(today).amount(new BigDecimal("40.00"))
                .kind(PayableDocumentEntity.KIND_RENT).otherRentId(3L).period("2026-02").build());
        Long methodId = method().getId();

        PaymentRow supplierPayment = payments.createPayment(pay(methodId, order.getId(), null, "80.00"));
        PaymentRow rentPayment = payments.createPayment(pay(methodId, null, rent.getId(), "40.00"));
        assertEquals(2, paymentRepository.count());

        PaymentsResponse history = new SupplierPaymentController(payments, null).payments().getBody();
        assertEquals(2, history.rows().size());
        PaymentRow historySupplier = history.rows().stream()
                .filter(row -> supplierPayment.id().equals(row.id())).findFirst().orElseThrow();
        PaymentRow historyRent = history.rows().stream()
                .filter(row -> rentPayment.id().equals(row.id())).findFirst().orElseThrow();
        assertFalse(historySupplier.rentPayment());
        assertTrue(historyRent.rentPayment());
    }

    private void setPaymentDay(Long locationId, Long otherRentId, int day) {
        KioskRentEntity rent = rentRepository.findById(locationId).orElseThrow();
        rent.setPaymentDay(day);
        rentRepository.save(rent);
        OtherRentEntity other = otherRentRepository.findById(otherRentId).orElseThrow();
        other.setPaymentDay(day);
        otherRentRepository.save(other);
    }

    private LocationEntity kiosk(String code, String name, String landlord, int day, BigDecimal financeAmount,
                                 LocalDate start, LocalDate end) {
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
                .contractStart(start).contractEnd(end).build());
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
}
