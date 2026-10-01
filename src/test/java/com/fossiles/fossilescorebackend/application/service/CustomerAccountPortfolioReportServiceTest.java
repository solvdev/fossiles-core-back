package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioCustomerResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerAccountEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cartera de clientes: una fila por documento, cargos/pagos/créditos separados y totales que cuadran
 * con los saldos del listado de cuentas por cobrar ({@link CustomerAccountService#getSummary}).
 */
class CustomerAccountPortfolioReportServiceTest {

    private CustomerAccountEntryRepository entryRepository;
    private CustomerRepository customerRepository;
    private ProductionOrderRepository productionOrderRepository;
    private ProductShipmentRepository productShipmentRepository;
    private CustomerAccountPortfolioReportService reportService;

    private final List<CustomerEntity> customers = new ArrayList<>();
    private final List<ProductionOrderEntity> orders = new ArrayList<>();
    private final List<ProductShipmentEntity> shipments = new ArrayList<>();
    private final List<CustomerAccountEntryEntity> ledger = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1000);

    private static final LocalDate D1 = LocalDate.of(2026, 6, 16);

    @BeforeEach
    void setUp() {
        entryRepository = mock(CustomerAccountEntryRepository.class);
        customerRepository = mock(CustomerRepository.class);
        productionOrderRepository = mock(ProductionOrderRepository.class);
        productShipmentRepository = mock(ProductShipmentRepository.class);

        when(customerRepository.findAll()).thenAnswer(inv -> new ArrayList<>(customers));
        when(entryRepository.findAll()).thenAnswer(inv -> new ArrayList<>(ledger));
        when(entryRepository.findLuisFelipeReceivableCustomerIds()).thenAnswer(inv ->
                orders.stream().map(ProductionOrderEntity::getCustomerId).distinct().collect(Collectors.toList()));
        when(entryRepository.findCustomerIdsWithLfOrderKindEntries()).thenReturn(List.of());
        when(productionOrderRepository.findAll()).thenAnswer(inv -> new ArrayList<>(orders));
        when(productionOrderRepository.findById(any())).thenAnswer(inv -> orders.stream()
                .filter(o -> o.getId().equals(inv.getArgument(0))).findFirst());
        when(productionOrderRepository.findAllById(any())).thenAnswer(inv -> {
            Set<Long> wanted = new HashSet<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(wanted::add);
            return orders.stream().filter(o -> wanted.contains(o.getId())).collect(Collectors.toList());
        });
        when(productShipmentRepository.findAllById(any())).thenAnswer(inv -> {
            Set<Long> wanted = new HashSet<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(wanted::add);
            return shipments.stream().filter(s -> wanted.contains(s.getId())).collect(Collectors.toList());
        });

        CustomerAccountService accountService = new CustomerAccountService(
                entryRepository, customerRepository, productionOrderRepository,
                null, null, null, productShipmentRepository, null, null, null, null, null);
        reportService = new CustomerAccountPortfolioReportService(
                accountService, entryRepository, productionOrderRepository, productShipmentRepository);
    }

    // ---- escenarios ------------------------------------------------------------------------

    @Test
    void documentWithMultiplePartialPayments_isOneRowWithSplitAmounts() throws Exception {
        long cust = customer("Tienda Uno");
        long order = order(cust, "OPV-1", "NORMAL", "ENVP-1");
        long charge = charge(cust, order, null, null, "1000.00", "ENVP-1", D1);
        payment(cust, charge, "300.00", D1.plusDays(5));
        payment(cust, charge, "200.00", D1.plusDays(9));
        creditNote(cust, charge, "100.00");

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(1);
        CustomerAccountPortfolioRowResponse row = report.getRows().get(0);
        assertThat(row.getChargedAmount()).isEqualByComparingTo("1000.00");
        assertThat(row.getPaymentsApplied()).isEqualByComparingTo("500.00");
        assertThat(row.getCreditsApplied()).isEqualByComparingTo("100.00");
        assertThat(row.getBalanceDue()).isEqualByComparingTo("400.00");
        assertThat(row.getPaymentCount()).isEqualTo(2);
        assertThat(row.getCreditCount()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo("PARTIAL");
        assertThat(row.getLastPaymentDate()).isEqualTo(D1.plusDays(9));
        assertBalanceFormula(report);
        assertReconciled(report);
    }

    @Test
    void paymentWithCollectionDiscount_separatesCashFromDiscount() throws Exception {
        long cust = customer("Tienda Dos");
        long order = order(cust, "OPV-2", "NORMAL", "ENVP-2");
        long charge = charge(cust, order, null, null, "1000.00", "ENVP-2", D1);
        CustomerAccountEntryEntity pay = payment(cust, charge, "900.00", D1.plusDays(3));
        pay.setGrossCollectedAmount(new BigDecimal("1000.00"));
        pay.setPaymentDiscountAmount(new BigDecimal("100.00"));

        CustomerAccountPortfolioReportResponse report = run("OPV");

        CustomerAccountPortfolioRowResponse row = report.getRows().get(0);
        assertThat(row.getPaymentsApplied()).isEqualByComparingTo("900.00");
        assertThat(row.getCreditsApplied()).isEqualByComparingTo("100.00");
        assertThat(row.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(row.getStatus()).isEqualTo("PAID");
        assertReconciled(report);
    }

    @Test
    void customerWithCreditsAndReturns_showsThemAsCredits() throws Exception {
        long cust = customer("Tienda Tres");
        long order = order(cust, "OPV-3", "NORMAL", "ENVP-3");
        long charge = charge(cust, order, null, null, "2000.00", "ENVP-3", D1);
        creditNote(cust, charge, "250.00");
        CustomerAccountEntryEntity ret = entry(cust, "RETURN", "150.00", D1.plusDays(2));
        ret.setAppliedToEntryId(charge);
        ret.setProductionOrderId(order);

        CustomerAccountPortfolioReportResponse report = run("OPV");

        CustomerAccountPortfolioRowResponse row = report.getRows().get(0);
        assertThat(row.getPaymentsApplied()).isEqualByComparingTo("0.00");
        assertThat(row.getCreditsApplied()).isEqualByComparingTo("400.00");
        assertThat(row.getBalanceDue()).isEqualByComparingTo("1600.00");
        assertBalanceFormula(report);
        assertReconciled(report);
    }

    @Test
    void zeroBalanceCustomer_isListedOnce_andHiddenByOnlyOpen() throws Exception {
        long paid = customer("Cliente Saldado");
        long paidOrder = order(paid, "OPV-4", "NORMAL", "ENVP-4");
        long paidCharge = charge(paid, paidOrder, null, null, "500.00", "ENVP-4", D1);
        payment(paid, paidCharge, "500.00", D1.plusDays(1));
        long open = customer("Cliente Abierto");
        long openOrder = order(open, "OPV-5", "NORMAL", "ENVP-5");
        charge(open, openOrder, null, null, "300.00", "ENVP-5", D1);

        CustomerAccountPortfolioReportResponse all = run("OPV");
        assertThat(all.getRows()).extracting(CustomerAccountPortfolioRowResponse::getCustomerId)
                .containsExactlyInAnyOrder(paid, open);
        CustomerAccountPortfolioCustomerResponse zero = all.getCustomers().stream()
                .filter(c -> c.getCustomerId() == paid).findFirst().orElseThrow();
        assertThat(zero.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(zero.getDocumentCount()).isEqualTo(1);
        assertReconciled(all);

        CustomerAccountPortfolioReportResponse onlyOpen = reportService.buildReport(
                null, "OPV", true, null, null, null, false, null, null);
        assertThat(onlyOpen.getRows()).extracting(CustomerAccountPortfolioRowResponse::getCustomerId)
                .containsExactly(open);
        assertThat(onlyOpen.getTotalBalanceDue()).isEqualByComparingTo("300.00");
        assertReconciled(onlyOpen);
    }

    @Test
    void voidedMovements_neverCount_butAppearInAnnex() throws Exception {
        long cust = customer("Cliente Anulados");
        long order = order(cust, "OPV-6", "NORMAL", "ENVP-6");
        long charge = charge(cust, order, null, null, "1000.00", "ENVP-6", D1);
        CustomerAccountEntryEntity voidedPayment = payment(cust, charge, "400.00", D1.plusDays(2));
        voidedPayment.setStatus("VOID");
        voidedPayment.setVoidReason("error de captura");
        long voidedCharge = charge(cust, order, null, 55L, "30.00", "ENVP-6", D1);
        ledgerEntry(voidedCharge).setStatus("VOID");
        payment(cust, charge, "100.00", D1.plusDays(4));

        CustomerAccountPortfolioReportResponse report = reportService.buildReport(
                null, "OPV", false, null, null, null, true, null, null);

        assertThat(report.getRows()).hasSize(1);
        CustomerAccountPortfolioRowResponse row = report.getRows().get(0);
        assertThat(row.getChargedAmount()).isEqualByComparingTo("1000.00");
        assertThat(row.getPaymentsApplied()).isEqualByComparingTo("100.00");
        assertThat(row.getBalanceDue()).isEqualByComparingTo("900.00");
        assertThat(row.isDuplicateCharges()).isFalse();
        assertReconciled(report);

        assertThat(report.isIncludesMovements()).isTrue();
        assertThat(report.getMovements()).hasSize(4);
        List<CustomerAccountPortfolioMovementResponse> voided = report.getMovements().stream()
                .filter(m -> "VOID".equals(m.getStatus())).toList();
        assertThat(voided).hasSize(2);
        assertThat(voided).allSatisfy(m -> {
            assertThat(m.getDebit()).isEqualByComparingTo("0.00");
            assertThat(m.getCredit()).isEqualByComparingTo("0.00");
        });
        // el anexo nunca aporta filas a la cartera
        assertThat(report.getRows()).hasSize(1);
    }

    @Test
    void creditAppliedToVoidedCharge_isExplicitAdjustment_andStillReconciles() throws Exception {
        long cust = customer("Cliente Cargo Anulado");
        long order = order(cust, "OPV-7", "NORMAL", "ENVP-7");
        long voided = charge(cust, order, null, null, "800.00", "ENVP-7", D1);
        payment(cust, voided, "300.00", D1.plusDays(1));
        ledgerEntry(voided).setStatus("VOID");
        long live = charge(cust, order, 7L, null, "200.00", "ENVP-7B", D1.plusDays(2));

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).extracting(CustomerAccountPortfolioRowResponse::getRowType)
                .containsExactlyInAnyOrder("DOCUMENT", "ORPHAN_CREDIT");
        CustomerAccountPortfolioCustomerResponse totals = report.getCustomers().get(0);
        assertThat(totals.getNetBalance()).isEqualByComparingTo("-100.00");
        assertThat(totals.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(totals.getCreditBalance()).isEqualByComparingTo("100.00");
        assertThat(report.getDocumentCount()).isEqualTo(1);
        assertReconciled(report);
        assertThat(live).isNotNull();
    }

    @Test
    void chargeOnCancelledOrder_stillCountsBecauseItIsInTheSystemBalance() throws Exception {
        long cust = customer("Cliente Orden Cancelada");
        long order = order(cust, "OPV-8", "NORMAL", "ENVP-8");
        orders.stream().filter(o -> o.getId() == order).findFirst().orElseThrow().setStatus("CANCELLED");
        charge(cust, order, null, null, "750.00", "ENVP-8", D1);

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getTotalBalanceDue()).isEqualByComparingTo("750.00");
        assertReconciled(report);
    }

    @Test
    void sameDocumentChargedAtOrderAndShipmentLevel_isOneRowFlaggedAsDuplicate() throws Exception {
        long cust = customer("Cliente Cargo Doble");
        long order = order(cust, "OPV-9", "NORMAL", "ENVP-24");
        long shipment = shipment(order, "ENVP-24");
        long legacy = charge(cust, order, null, null, "3175.00", "ENVP-24", D1);
        payment(cust, legacy, "3000.00", D1.plusDays(3));
        long later = charge(cust, order, null, shipment, "3175.00", "ENVP-24", D1.plusDays(52));

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(1);
        CustomerAccountPortfolioRowResponse row = report.getRows().get(0);
        assertThat(row.isDuplicateCharges()).isTrue();
        assertThat(row.getChargeCount()).isEqualTo(2);
        assertThat(row.getChargeEntryIds()).containsExactlyInAnyOrder(legacy, later);
        assertThat(row.getChargedAmount()).isEqualByComparingTo("6350.00");
        assertThat(row.getPaymentsApplied()).isEqualByComparingTo("3000.00");
        assertThat(row.getBalanceDue()).isEqualByComparingTo("3350.00");
        assertThat(row.getShipmentNumber()).isEqualTo("ENVP-24");
        assertThat(report.getDuplicateChargeDocuments()).isEqualTo(1);
        assertReconciled(report);
    }

    @Test
    void distinctShipmentsOfTheSameOrder_stayAsSeparateDocuments() throws Exception {
        long cust = customer("Cliente Dos Envios");
        long order = order(cust, "OPV-10", "NORMAL", "ENVP-30");
        long s1 = shipment(order, "ENVP-30-A");
        long s2 = shipment(order, "ENVP-30-B");
        charge(cust, order, null, s1, "100.00", "ENVP-30", D1);
        charge(cust, order, null, s2, "100.00", "ENVP-30", D1);

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(2);
        assertThat(report.getRows()).noneMatch(CustomerAccountPortfolioRowResponse::isDuplicateCharges);
        assertReconciled(report);
    }

    @Test
    void customerWithoutMovementsInPeriod_keepsItsBalance_andHasNoAnnexLines() throws Exception {
        long quiet = customer("Cliente Sin Movimientos");
        long quietOrder = order(quiet, "OPV-11", "NORMAL", "ENVP-11");
        charge(quiet, quietOrder, null, null, "400.00", "ENVP-11", LocalDate.of(2026, 1, 10));
        long active = customer("Cliente Activo");
        long activeOrder = order(active, "OPV-12", "NORMAL", "ENVP-12");
        charge(active, activeOrder, null, null, "600.00", "ENVP-12", LocalDate.of(2026, 7, 10));

        CustomerAccountPortfolioReportResponse report = reportService.buildReport(
                null, "OPV", false, null, null, null, true,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(report.getCustomers()).extracting(CustomerAccountPortfolioCustomerResponse::getCustomerId)
                .containsExactlyInAnyOrder(quiet, active);
        assertThat(report.getTotalBalanceDue()).isEqualByComparingTo("1000.00");
        assertThat(report.getMovements()).extracting(CustomerAccountPortfolioMovementResponse::getCustomerId)
                .containsOnly(active);
        assertReconciled(report);
    }

    @Test
    void unappliedCredits_areReportedApart_andDoNotBreakReconciliation() throws Exception {
        long cust = customer("Cliente Con Anticipo");
        long order = order(cust, "OPV-13", "NORMAL", "ENVP-13");
        charge(cust, order, null, null, "500.00", "ENVP-13", D1);
        CustomerAccountEntryEntity advance = entry(cust, "PAYMENT", "200.00", D1.plusDays(1));
        advance.setAppliedToEntryId(null);

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getTotalBalanceDue()).isEqualByComparingTo("500.00");
        assertThat(report.getUnappliedCreditsTotal()).isEqualByComparingTo("200.00");
        assertReconciled(report);
    }

    @Test
    void portfoliosAreSeparatedByKind() throws Exception {
        long fossiles = customer("Cliente Fossiles");
        long opvOrder = order(fossiles, "OPV-14", "NORMAL", "ENVP-14");
        charge(fossiles, opvOrder, null, null, "100.00", "ENVP-14", D1);
        long gcf = customer("Cliente GCF");
        long opcOrder = order(gcf, "OPC-1", "CINCHOS", "ENVP-15");
        charge(gcf, opcOrder, null, null, "250.00", "ENVP-15", D1);

        CustomerAccountPortfolioReportResponse opv = run("OPV");
        CustomerAccountPortfolioReportResponse opc = run("OPC");

        assertThat(opv.getCustomers()).extracting(CustomerAccountPortfolioCustomerResponse::getCustomerId)
                .containsExactly(fossiles);
        assertThat(opc.getCustomers()).extracting(CustomerAccountPortfolioCustomerResponse::getCustomerId)
                .containsExactly(gcf);
        assertReconciled(opv);
        assertReconciled(opc);
    }

    @Test
    void largeDataset_hasNoDuplicatedRows_andTotalsMatchTheSystem() throws Exception {
        int customerCount = 700;
        for (int i = 0; i < customerCount; i++) {
            long cust = customer("Cliente " + i);
            long order = order(cust, "OPV-L" + i, "NORMAL", "ENVP-L" + i);
            for (int d = 0; d < 3; d++) {
                long shipment = shipment(order, "ENVP-L" + i + "-" + d);
                long charge = charge(cust, order, null, shipment, "100.00", "ENVP-L" + i, D1.plusDays(d));
                if (d == 0) {
                    payment(cust, charge, "40.00", D1.plusDays(10));
                    payment(cust, charge, "20.00", D1.plusDays(11));
                } else if (d == 1) {
                    creditNote(cust, charge, "100.00");
                }
            }
        }

        CustomerAccountPortfolioReportResponse report = run("OPV");

        assertThat(report.getRows()).hasSize(customerCount * 3);
        Set<Long> chargeIds = new HashSet<>();
        report.getRows().forEach(r -> r.getChargeEntryIds().forEach(id -> assertThat(chargeIds.add(id)).isTrue()));
        assertThat(report.getCustomerCount()).isEqualTo(customerCount);
        assertThat(report.getTotalCharged()).isEqualByComparingTo(String.valueOf(customerCount * 300) + ".00");
        assertThat(report.getTotalPayments()).isEqualByComparingTo(String.valueOf(customerCount * 60) + ".00");
        assertThat(report.getTotalCredits()).isEqualByComparingTo(String.valueOf(customerCount * 100) + ".00");
        assertThat(report.getTotalBalanceDue()).isEqualByComparingTo(String.valueOf(customerCount * 140) + ".00");
        assertBalanceFormula(report);
        assertReconciled(report);
    }

    @Test
    void searchByDocument_returnsOnlyMatchingDocuments() throws Exception {
        long cust = customer("Cliente Busqueda");
        long order = order(cust, "OPV-S1", "NORMAL", "ENVP-S1");
        long order2 = order(cust, "OPV-S2", "NORMAL", "ENVP-S2");
        charge(cust, order, null, null, "100.00", "ENVP-S1", D1);
        charge(cust, order2, null, null, "200.00", "ENVP-S2", D1);

        CustomerAccountPortfolioReportResponse report = reportService.buildReport(
                "envp-s2", "OPV", false, null, null, null, false, null, null);

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().get(0).getInvoiceNumber()).isEqualTo("ENVP-S2");
    }

    @Test
    void invalidKind_isRejected() {
        assertThatThrownBy(() -> reportService.buildReport(
                null, "XYZ", false, null, null, null, false, null, null))
                .isInstanceOf(BusinessException.class);
    }

    // ---- aserciones comunes ------------------------------------------------------------------

    private void assertBalanceFormula(CustomerAccountPortfolioReportResponse report) {
        report.getRows().forEach(r -> assertThat(r.getBalanceDue()).isEqualByComparingTo(
                r.getChargedAmount().subtract(r.getPaymentsApplied()).subtract(r.getCreditsApplied())));
    }

    /** Totales del reporte = saldos que muestra el listado de cuentas por cobrar, cliente por cliente. */
    private void assertReconciled(CustomerAccountPortfolioReportResponse report) throws Exception {
        assertThat(report.isReconciled()).as("reconciled (difference=%s)", report.getDifference()).isTrue();
        assertThat(report.getDifference()).isEqualByComparingTo("0.00");
        CustomerAccountService accountService = new CustomerAccountService(
                entryRepository, customerRepository, productionOrderRepository,
                null, null, null, productShipmentRepository, null, null, null, null, null);
        List<CustomerAccountSummaryResponse> summaries = accountService.getSummary(null, true, false);
        BigDecimal expected = BigDecimal.ZERO;
        for (CustomerAccountPortfolioCustomerResponse customer : report.getCustomers()) {
            CustomerAccountSummaryResponse summary = summaries.stream()
                    .filter(s -> s.getCustomerId().equals(customer.getCustomerId())).findFirst().orElseThrow();
            BigDecimal system = "OPC".equals(report.getOrderKind()) ? summary.getBalanceDueOpc() : summary.getBalanceDueOpv();
            assertThat(customer.getBalanceDue()).as("cliente %s", customer.getCustomerName())
                    .isEqualByComparingTo(system);
            expected = expected.add(system);
        }
        assertThat(report.getTotalBalanceDue()).isEqualByComparingTo(expected);
    }

    // ---- fixtures --------------------------------------------------------------------------

    private CustomerAccountPortfolioReportResponse run(String kind) throws BusinessException {
        return reportService.buildReport(null, kind, false, null, null, null, false, null, null);
    }

    private long customer(String name) {
        long id = ids.incrementAndGet();
        customers.add(CustomerEntity.builder().id(id).name(name).legacyCode("C" + id).nit("NIT" + id).build());
        return id;
    }

    private long order(long customerId, String code, String type, String vendorShipment) {
        long id = ids.incrementAndGet();
        orders.add(ProductionOrderEntity.builder()
                .id(id).code(code).orderType(type).customerId(customerId)
                .sellerName("LUIS FELIPE").vendorShipmentNumber(vendorShipment).status("COMPLETED").build());
        return id;
    }

    private long shipment(long orderId, String number) {
        long id = ids.incrementAndGet();
        shipments.add(ProductShipmentEntity.builder().id(id).shipmentNumber(number).build());
        return id;
    }

    private CustomerAccountEntryEntity entry(long customerId, String type, String amount, LocalDate date) {
        CustomerAccountEntryEntity entry = CustomerAccountEntryEntity.builder()
                .id(ids.incrementAndGet()).customerId(customerId).entryType(type).status("ACTIVE")
                .amount(new BigDecimal(amount)).entryDate(date).build();
        ledger.add(entry);
        return entry;
    }

    private long charge(long customerId, long orderId, Long releaseId, Long shipmentId, String amount,
                        String invoice, LocalDate date) {
        CustomerAccountEntryEntity charge = entry(customerId, "CHARGE", amount, date);
        charge.setProductionOrderId(orderId);
        charge.setPartialReleaseId(releaseId);
        charge.setProductShipmentId(shipmentId);
        charge.setInvoiceNumber(invoice);
        charge.setOrderKind("OPV");
        return charge.getId();
    }

    private CustomerAccountEntryEntity payment(long customerId, long chargeId, String amount, LocalDate date) {
        return applied(customerId, "PAYMENT", chargeId, amount, date);
    }

    private void creditNote(long customerId, long chargeId, String amount) {
        applied(customerId, "CREDIT_NOTE", chargeId, amount, D1.plusDays(1));
    }

    private CustomerAccountEntryEntity applied(long customerId, String type, long chargeId, String amount, LocalDate date) {
        CustomerAccountEntryEntity target = ledgerEntry(chargeId);
        CustomerAccountEntryEntity entry = entry(customerId, type, amount, date);
        entry.setAppliedToEntryId(chargeId);
        entry.setProductionOrderId(target.getProductionOrderId());
        entry.setProductShipmentId(target.getProductShipmentId());
        entry.setOrderKind(target.getOrderKind());
        return entry;
    }

    private CustomerAccountEntryEntity ledgerEntry(long id) {
        return ledger.stream().filter(e -> e.getId() == id).findFirst().orElseThrow();
    }
}
