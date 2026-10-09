package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountStatementLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OrderChargeQuoteResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.controller.CustomerController;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerAccountEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderPartialReleaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ledger rules on H2. No unique index: the service checks stand alone (phase 2 may be absent).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lf_ledger;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class CustomerAccountLedgerRulesTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired CustomerAccountService accounts;
    @Autowired OpvShipmentCatalogService catalog;
    @Autowired CustomerController customers;
    @Autowired CustomerAccountEntryRepository entries;
    @Autowired ProductionOrderRepository orders;
    @Autowired ProductionOrderItemRepository items;
    @Autowired ProductionOrderPartialReleaseRepository releases;
    @Autowired ProductShipmentRepository shipments;
    @Autowired ProductShipmentDetailRepository details;
    @Autowired ProductRepository products;

    private ProductEntity product;

    @BeforeEach
    void product() {
        int n = SEQ.incrementAndGet();
        product = products.save(ProductEntity.builder()
                .code("LF-R-" + n)
                .name("Producto")
                .requiresMaterials(false)
                .build());
    }

    @Test
    void perPartialChargeIsRejectedAndASecondChargeIsRejected() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "1000.00", "500.00");
        ProductionOrderPartialReleaseEntity partial2 = release(order, 2);
        ProductShipmentEntity shipment2 = shipment(order, partial2, "40.00", null);

        assertThatThrownBy(() -> charge(customer, order, partial2, shipment2, "500.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parcial");

        CustomerAccountEntryResponse created = charge(customer, order, null, null, "1500.00");
        assertThat(created.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(created.getPartialReleaseId()).isNull();
        assertThat(created.getProductShipmentId()).isNull();

        assertThatThrownBy(() -> charge(customer, order, null, null, "1500.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Ya existe un cargo activo");
        assertThat(activeCharges(customer)).hasSize(1);
    }

    @Test
    void chargeWithoutOrderIsRejected() throws Exception {
        CustomerEntity customer = customer();
        assertThatThrownBy(() -> charge(customer, null, null, null, "10.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("requiere una orden");
    }

    @Test
    void chargeAboveOrderTotalIsRejectedAndServerStoresProductsOnly() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "1000.00", "500.00");
        shipment(order, release(order, 1), "1000.00", "75.00");
        shipment(order, release(order, 2), "500.00", "40.00");
        ProductionOrderPartialReleaseEntity planned = release(order, 3);
        order.setObservations("__OPV_SHIPPING__:50.00");
        orders.save(order);

        OrderChargeQuoteResponse quote = accounts.quoteOrderCharge(order.getId());
        assertThat(quote.getProductsTotal()).isEqualByComparingTo("1500.00");
        assertThat(quote.getShippingTotal()).isEqualByComparingTo("115.00");
        assertThat(quote.getOrderTotal()).isEqualByComparingTo("1615.00");
        assertThat(quote.getAmount()).isEqualByComparingTo("1500.00");

        assertThatThrownBy(() -> charge(customer, order, null, null, "5000.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("supera el total");

        CustomerAccountEntryResponse created = charge(customer, order, null, null, "1.00");
        assertThat(entries.findById(created.getId()).orElseThrow().getAmount()).isEqualByComparingTo("1500.00");
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("1500.00");

        assertThat(accounts.searchReceivables(null, null, null, null, null, null, null, null, false, 50).stream()
                .filter(row -> planned.getId().equals(row.getPartialReleaseId()))
                .findFirst().orElseThrow().getEstimatedTotal()).isNull();
    }

    @Test
    void shippingCostMatchesOnCatalogReceivablesAndLfDocuments() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "1000.00", "500.00");
        ProductShipmentEntity first = shipment(order, release(order, 1), "1000.00", "75.00");
        ProductShipmentEntity second = shipment(order, release(order, 2), "500.00", "40.00");

        assertThat(catalogRow(customer, first).getEstimatedTotal()).isEqualByComparingTo("1075.00");
        assertThat(catalogRow(customer, second).getEstimatedTotal()).isEqualByComparingTo("540.00");
        assertThat(receivableEstimate(customer, first)).isEqualByComparingTo("1075.00");
        assertThat(receivableEstimate(customer, second)).isEqualByComparingTo("540.00");
        LfSalesDocumentResponse document = accounts.getLfDocuments(customer.getId(), false).get(0);
        assertThat(document.getEstimatedTotal()).isEqualByComparingTo("1615.00");
    }

    @Test
    void chargedOrderDoesNotOfferAnotherCharge() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "200.00");
        ProductShipmentEntity shipment = shipment(order, release(order, 1), "200.00", null);
        charge(customer, order, null, null, "200.00");

        assertThat(catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(catalogRow(customer, shipment).getChargeStatus()).isNotEqualTo("NONE");
        assertThat(accounts.searchReceivables(null, null, null, null, null, null, null, null, false, 50).stream()
                .filter(row -> shipment.getId().equals(row.getProductShipmentId()))
                .findFirst().orElseThrow().isHasCharge()).isTrue();
    }

    @Test
    void adjustmentCopiesChargeAndSecondAdjustmentIsRejected() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity opc = order(customer, "CINCHOS", "80.00");
        ProductionOrderEntity other = order(customer, "OPV", "10.00");
        ProductShipmentEntity shipment = shipment(opc, release(opc, 1), "80.00", "25.00");
        CustomerAccountEntryResponse charge = charge(customer, opc, null, null, "80.00");

        CustomerAccountEntryRequest adjustment = base("CHARGE_ADJUSTMENT", "1.00");
        adjustment.setProductShipmentId(shipment.getId());
        adjustment.setProductionOrderId(other.getId());
        CustomerAccountEntryResponse saved = accounts.createEntry(customer.getId(), adjustment);

        CustomerAccountEntryEntity row = entries.findById(saved.getId()).orElseThrow();
        assertThat(row.getAmount()).isEqualByComparingTo("25.00");
        assertThat(row.getAppliedToEntryId()).isEqualTo(charge.getId());
        assertThat(row.getProductionOrderId()).isEqualTo(opc.getId());
        assertThat(row.getProductShipmentId()).isEqualTo(shipment.getId());
        assertThat(row.getOrderKind()).isEqualTo("OPC");
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("105.00");

        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), adjustment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ajuste");
    }

    @Test
    void adjustmentMissingOrderShipmentOrChargeIsRejected() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "40.00");
        ProductShipmentEntity shipped = shipment(order, release(order, 1), "40.00", "12.00");

        CustomerAccountEntryRequest noShipment = base("CHARGE_ADJUSTMENT", "12.00");
        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), noShipment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("envío");

        ProductShipmentEntity noOrder = shipments.save(ProductShipmentEntity.builder()
                .shipmentNumber("ENV-SUELTO-" + SEQ.incrementAndGet())
                .status("SENT")
                .shippingCost(new BigDecimal("12.00"))
                .build());
        CustomerAccountEntryRequest noOrderRequest = base("CHARGE_ADJUSTMENT", "12.00");
        noOrderRequest.setProductShipmentId(noOrder.getId());
        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), noOrderRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("orden");

        CustomerAccountEntryRequest noCharge = base("CHARGE_ADJUSTMENT", "12.00");
        noCharge.setProductShipmentId(shipped.getId());
        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), noCharge))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cargo");

        assertThat(entries.findByCustomerIdOrderByEntryDateAscIdAsc(customer.getId()).stream()
                .filter(entry -> "CHARGE_ADJUSTMENT".equals(entry.getEntryType()))
                .count()).isZero();
    }

    @Test
    void paymentCopiesOrderFromChargeAndIsCappedAtOpenBalance() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity opv = order(customer, "OPV", "100.00");
        ProductionOrderEntity opc = order(customer, "MARCAS", "40.00");
        ProductShipmentEntity shipment = shipment(opv, release(opv, 1), "100.00", "40.00");
        CustomerAccountEntryResponse charge = charge(customer, opv, null, null, "100.00");
        CustomerAccountEntryRequest adjustment = base("CHARGE_ADJUSTMENT", "1.00");
        adjustment.setProductShipmentId(shipment.getId());
        accounts.createEntry(customer.getId(), adjustment);

        CustomerAccountEntryRequest unlinked = payment(null, "10.00");
        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), unlinked))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("debe aplicarse");

        CustomerAccountEntryRequest over = payment(charge.getId(), "140.01");
        over.setProductionOrderId(opc.getId());
        assertThatThrownBy(() -> accounts.createEntry(customer.getId(), over))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("excede el saldo");

        CustomerAccountEntryRequest payment = payment(charge.getId(), "30.00");
        payment.setProductionOrderId(opc.getId());
        CustomerAccountEntryResponse saved = accounts.createEntry(customer.getId(), payment);
        CustomerAccountEntryEntity row = entries.findById(saved.getId()).orElseThrow();
        assertThat(row.getProductionOrderId()).isEqualTo(opv.getId());
        assertThat(row.getOrderKind()).isEqualTo("OPV");
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("110.00");
    }

    @Test
    void voidWithoutReassignIsRejectedWhenCreditsExist() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "90.00");
        CustomerAccountEntryResponse charge = charge(customer, order, null, null, "90.00");
        accounts.createEntry(customer.getId(), payment(charge.getId(), "10.00"));

        assertThatThrownBy(() -> accounts.voidEntry(charge.getId(), voidRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("trasladarse")
                .hasMessageNotContaining("Anúlelo")
                .hasMessageNotContaining("registrarlo de nuevo");
        assertThat(entries.findById(charge.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void voidReassignsCreditsOnTheSameOrderAndOnAnotherKind() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "100.00");
        CustomerAccountEntryResponse keeper = charge(customer, order, null, null, "100.00");
        CustomerAccountEntryEntity mistaken = entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE")
                .status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 1))
                .amount(new BigDecimal("60.00"))
                .productionOrderId(order.getId())
                .orderKind("OPV")
                .build());
        ProductShipmentEntity shipment = shipment(order, release(order, 1), "60.00", "15.00");
        entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE_ADJUSTMENT")
                .status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 2))
                .amount(new BigDecimal("15.00"))
                .productionOrderId(order.getId())
                .productShipmentId(shipment.getId())
                .appliedToEntryId(mistaken.getId())
                .orderKind("OPV")
                .build());
        CustomerAccountEntryResponse payment = accounts.createEntry(customer.getId(), payment(mistaken.getId(), "20.00"));
        BigDecimal before = accounts.getBalance(customer.getId()).getBalance();

        accounts.voidEntry(mistaken.getId(), voidRequest(keeper.getId()));

        assertThat(entries.findById(mistaken.getId()).orElseThrow().getStatus()).isEqualTo("VOID");
        CustomerAccountEntryEntity movedPayment = entries.findById(payment.getId()).orElseThrow();
        assertThat(movedPayment.getStatus()).isEqualTo("ACTIVE");
        assertThat(movedPayment.getAppliedToEntryId()).isEqualTo(keeper.getId());
        assertThat(movedPayment.getProductionOrderId()).isEqualTo(order.getId());
        assertThat(movedPayment.getOrderKind()).isEqualTo("OPV");
        assertThat(movedPayment.getReassignedFromEntryId()).isEqualTo(mistaken.getId());
        assertThat(movedPayment.getDescription()).isEqualTo("cargo equivocado");
        assertThat(line(customer, payment.getId()).getReassignedFromEntryId()).isEqualTo(mistaken.getId());
        CustomerAccountEntryEntity movedAdjustment = entries.findByCustomerIdOrderByEntryDateAscIdAsc(customer.getId()).stream()
                .filter(entry -> "CHARGE_ADJUSTMENT".equals(entry.getEntryType()))
                .findFirst().orElseThrow();
        assertThat(movedAdjustment.getAppliedToEntryId()).isEqualTo(keeper.getId());
        assertThat(movedAdjustment.getProductShipmentId()).isEqualTo(shipment.getId());
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo(before.subtract(new BigDecimal("60.00")));

        CustomerAccountEntryEntity next = entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE")
                .status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 3))
                .amount(new BigDecimal("100.00"))
                .productionOrderId(order.getId())
                .orderKind("OPV")
                .build());
        CustomerAccountEntryVoidRequest second = voidRequest(next.getId());
        second.setVoidReason("segundo traslado");
        accounts.voidEntry(keeper.getId(), second);
        movedPayment = entries.findById(payment.getId()).orElseThrow();
        assertThat(movedPayment.getAppliedToEntryId()).isEqualTo(next.getId());
        assertThat(movedPayment.getReassignedFromEntryId()).isEqualTo(mistaken.getId());
        assertThat(movedPayment.getDescription()).isEqualTo("cargo equivocado\nsegundo traslado");
        assertThat(line(customer, payment.getId()).getReassignedFromEntryId()).isEqualTo(mistaken.getId());

        ProductionOrderEntity opv = order(customer, "OPV", "80.00");
        ProductionOrderEntity opc = order(customer, "MARCAS", "200.00");
        CustomerAccountEntryResponse opvCharge = charge(customer, opv, null, null, "80.00");
        CustomerAccountEntryResponse opcCharge = charge(customer, opc, null, null, "200.00");
        CustomerAccountEntryResponse cross = accounts.createEntry(customer.getId(), payment(opvCharge.getId(), "25.00"));
        accounts.voidEntry(opvCharge.getId(), voidRequest(opcCharge.getId()));
        CustomerAccountEntryEntity crossPayment = entries.findById(cross.getId()).orElseThrow();
        assertThat(crossPayment.getAppliedToEntryId()).isEqualTo(opcCharge.getId());
        assertThat(crossPayment.getProductionOrderId()).isEqualTo(opc.getId());
        assertThat(crossPayment.getOrderKind()).isEqualTo("OPC");
        assertThat(entries.findById(opvCharge.getId()).orElseThrow().getStatus()).isEqualTo("VOID");
    }

    @Test
    void voidReassignFitsOnlyBecauseTheAdjustmentMoves() throws Exception {
        CustomerEntity customer = customer();
        ProductionOrderEntity order = order(customer, "OPV", "60.00");
        CustomerAccountEntryResponse target = charge(customer, order, null, null, "60.00");
        CustomerAccountEntryEntity source = entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE")
                .status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 1))
                .amount(new BigDecimal("100.00"))
                .productionOrderId(order.getId())
                .orderKind("OPV")
                .build());
        ProductShipmentEntity shipment = shipment(order, release(order, 1), "40.00", "40.00");
        entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE_ADJUSTMENT")
                .status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 2))
                .amount(new BigDecimal("40.00"))
                .productionOrderId(order.getId())
                .productShipmentId(shipment.getId())
                .appliedToEntryId(source.getId())
                .orderKind("OPV")
                .build());
        CustomerAccountEntryResponse payment = accounts.createEntry(customer.getId(), payment(source.getId(), "90.00"));

        accounts.voidEntry(source.getId(), voidRequest(target.getId()));

        assertThat(entries.findById(source.getId()).orElseThrow().getStatus()).isEqualTo("VOID");
        assertThat(entries.findById(payment.getId()).orElseThrow().getAppliedToEntryId()).isEqualTo(target.getId());
        assertThat(entries.findByCustomerIdOrderByEntryDateAscIdAsc(customer.getId()).stream()
                .filter(entry -> "CHARGE_ADJUSTMENT".equals(entry.getEntryType()))
                .findFirst().orElseThrow().getAppliedToEntryId()).isEqualTo(target.getId());
    }

    @Test
    void voidReassignRejectsBadTargetsAndLeavesRowsUntouched() throws Exception {
        CustomerEntity customer = customer();
        CustomerEntity other = customer();
        ProductionOrderEntity sourceOrder = order(customer, "OPV", "100.00");
        ProductionOrderEntity smallOrder = order(customer, "OPV", "50.00");
        ProductionOrderEntity otherOrder = order(other, "OPV", "100.00");
        CustomerAccountEntryResponse source = charge(customer, sourceOrder, null, null, "100.00");
        CustomerAccountEntryResponse small = charge(customer, smallOrder, null, null, "50.00");
        CustomerAccountEntryResponse foreign = charge(other, otherOrder, null, null, "100.00");
        CustomerAccountEntryResponse payment = accounts.createEntry(customer.getId(), payment(source.getId(), "80.00"));

        assertThatThrownBy(() -> accounts.voidEntry(source.getId(), voidRequest(small.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("supera el saldo");
        assertThat(entries.findById(source.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(entries.findById(payment.getId()).orElseThrow().getAppliedToEntryId()).isEqualTo(source.getId());

        assertThatThrownBy(() -> accounts.voidEntry(source.getId(), voidRequest(foreign.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no pertenece");

        accounts.voidEntry(small.getId(), voidRequest(null));
        assertThatThrownBy(() -> accounts.voidEntry(source.getId(), voidRequest(small.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cargo activo");

        ProductShipmentEntity shipment = shipment(sourceOrder, release(sourceOrder, 1), "100.00", "10.00");
        CustomerAccountEntryResponse adjustment = accounts.createEntry(customer.getId(), adjustment(shipment.getId()));
        ProductionOrderEntity sibling = order(customer, "MARCAS", "100.00");
        CustomerAccountEntryResponse siblingCharge = charge(customer, sibling, null, null, "100.00");
        assertThatThrownBy(() -> accounts.voidEntry(source.getId(), voidRequest(siblingCharge.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ajustes de envío");
        assertThat(entries.findById(adjustment.getId()).orElseThrow().getAppliedToEntryId()).isEqualTo(source.getId());
        assertThat(entries.findById(source.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void agingAppliesOldestDueFirstAndRecomputesAfterVoid() throws Exception {
        CustomerEntity customer = customer();
        customers.update(customer.getId(), creditDays(0));
        ProductionOrderEntity order = order(customer, "OPV", "100.00");
        CustomerAccountEntryResponse charge = charge(customer, order, null, null, "100.00");
        ProductShipmentEntity later = shipment(order, release(order, 1), "30.00", "30.00");
        later.setSentAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        shipments.save(later);
        ProductShipmentEntity earlier = shipment(order, release(order, 2), "50.00", "50.00");
        earlier.setSentAt(LocalDateTime.of(2026, 1, 15, 8, 0));
        shipments.save(earlier);
        accounts.createEntry(customer.getId(), adjustment(later.getId()));
        accounts.createEntry(customer.getId(), adjustment(earlier.getId()));
        CustomerAccountEntryResponse payment = accounts.createEntry(customer.getId(), payment(charge.getId(), "120.00"));

        CustomerAccountStatementLineResponse chargeLine = line(customer, charge.getId());
        assertThat(chargeLine.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(chargeLine.getAllocatedCredit()).isEqualByComparingTo("100.00");
        assertThat(chargeLine.getLineOpenBalance()).isEqualByComparingTo("0.00");
        CustomerAccountStatementLineResponse early = lineForShipment(customer, earlier.getId());
        assertThat(early.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(early.getAllocatedCredit()).isEqualByComparingTo("20.00");
        assertThat(early.getLineOpenBalance()).isEqualByComparingTo("30.00");
        CustomerAccountStatementLineResponse late = lineForShipment(customer, later.getId());
        assertThat(late.getAllocatedCredit()).isEqualByComparingTo("0.00");
        assertThat(late.getLineOpenBalance()).isEqualByComparingTo("30.00");

        CustomerAccountEntryVoidRequest voidRequest = new CustomerAccountEntryVoidRequest();
        voidRequest.setVoidReason("anula el pago");
        accounts.voidEntry(payment.getId(), voidRequest);
        assertThat(line(customer, charge.getId()).getLineOpenBalance()).isEqualByComparingTo("100.00");
        assertThat(lineForShipment(customer, earlier.getId()).getAllocatedCredit()).isEqualByComparingTo("0.00");
        assertThat(lineForShipment(customer, later.getId()).getLineOpenBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void creditDaysBounds() throws Exception {
        CustomerEntity customer = customer();
        assertThat(customers.getById(customer.getId()).getBody().getCreditDays()).isZero();
        assertThat(customers.update(customer.getId(), creditDays(0)).getBody().getCreditDays()).isZero();
        assertThat(customers.update(customer.getId(), creditDays(60)).getBody().getCreditDays()).isEqualTo(60);
        assertThat(customers.getAll().getBody().stream()
                .filter(row -> customer.getId().equals(row.getId()))
                .findFirst().orElseThrow().getCreditDays()).isEqualTo(60);
        assertThatThrownBy(() -> customers.update(customer.getId(), creditDays(-1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> customers.update(customer.getId(), creditDays(61)))
                .isInstanceOf(BusinessException.class);
    }

    private CustomerEntity customer() throws Exception {
        int n = SEQ.incrementAndGet();
        Long id = customers.create(request("Cliente " + n, "C" + n)).getBody().getId();
        CustomerEntity entity = new CustomerEntity();
        entity.setId(id);
        entity.setCreditDays(0);
        return entity;
    }

    private CustomerRequest request(String name, String legacy) {
        CustomerRequest request = new CustomerRequest();
        request.setName(name);
        request.setLegacyCode(legacy);
        request.setStatus("active");
        return request;
    }

    private CustomerRequest creditDays(int days) {
        CustomerRequest request = new CustomerRequest();
        request.setCreditDays(days);
        return request;
    }

    private ProductionOrderEntity order(CustomerEntity customer, String type, String... prices) {
        int n = SEQ.incrementAndGet();
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code("OP-" + n)
                .orderType(type)
                .customerId(customer.getId())
                .sellerName("LUIS FELIPE")
                .vendorShipmentNumber("ENVP-" + n)
                .status("IN_PROGRESS")
                .build());
        for (String price : prices) {
            items.save(ProductionOrderItemEntity.builder()
                    .productionOrderId(order.getId())
                    .productId(product.getId())
                    .quantity(1)
                    .unitPrice(new BigDecimal(price))
                    .build());
        }
        return order;
    }

    private ProductionOrderPartialReleaseEntity release(ProductionOrderEntity order, int sequence) {
        return releases.save(ProductionOrderPartialReleaseEntity.builder()
                .productionOrderId(order.getId())
                .sequenceNum(sequence)
                .label("Parcial " + sequence)
                .status("RELEASED")
                .build());
    }

    private ProductShipmentEntity shipment(
            ProductionOrderEntity order, ProductionOrderPartialReleaseEntity release, String linePrice, String shipping) {
        int n = SEQ.incrementAndGet();
        ProductShipmentEntity shipment = shipments.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .partialReleaseId(release.getId())
                .shipmentNumber("ENV-" + n)
                .status("SENT")
                .shippingCost(shipping == null ? null : new BigDecimal(shipping))
                .build());
        details.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .sizeLabel("")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal(linePrice))
                .build());
        return shipment;
    }

    private CustomerAccountEntryResponse charge(
            CustomerEntity customer, ProductionOrderEntity order, ProductionOrderPartialReleaseEntity release,
            ProductShipmentEntity shipment, String amount) throws Exception {
        CustomerAccountEntryRequest request = base("CHARGE", amount);
        request.setProductionOrderId(order == null ? null : order.getId());
        request.setPartialReleaseId(release == null ? null : release.getId());
        request.setProductShipmentId(shipment == null ? null : shipment.getId());
        return accounts.createEntry(customer.getId(), request);
    }

    private static CustomerAccountEntryRequest payment(Long chargeId, String amount) {
        CustomerAccountEntryRequest request = base("PAYMENT", amount);
        request.setAppliedToEntryId(chargeId);
        request.setGrossCollectedAmount(new BigDecimal(amount));
        request.setMovementConceptCode(chargeId == null ? "4" : "11");
        request.setReceiptNumber("REC-" + amount);
        request.setCollectionDate(LocalDate.of(2026, 9, 1));
        return request;
    }

    private static CustomerAccountEntryRequest adjustment(Long shipmentId) {
        CustomerAccountEntryRequest request = base("CHARGE_ADJUSTMENT", "1.00");
        request.setProductShipmentId(shipmentId);
        return request;
    }

    private static CustomerAccountEntryVoidRequest voidRequest(Long reassignToChargeId) {
        CustomerAccountEntryVoidRequest request = new CustomerAccountEntryVoidRequest();
        request.setVoidReason("cargo equivocado");
        request.setReassignToChargeId(reassignToChargeId);
        return request;
    }

    private static CustomerAccountEntryRequest base(String type, String amount) {
        CustomerAccountEntryRequest request = new CustomerAccountEntryRequest();
        request.setEntryType(type);
        request.setEntryDate(LocalDate.of(2026, 9, 1));
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private List<CustomerAccountEntryEntity> activeCharges(CustomerEntity customer) {
        return entries.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customer.getId(), "ACTIVE").stream()
                .filter(entry -> "CHARGE".equals(entry.getEntryType()))
                .toList();
    }

    private CustomerAccountStatementLineResponse line(CustomerEntity customer, Long entryId) throws Exception {
        return accounts.getStatement(customer.getId(), null, null).getLines().stream()
                .filter(line -> entryId.equals(line.getId()))
                .findFirst().orElseThrow();
    }

    private CustomerAccountStatementLineResponse lineForShipment(CustomerEntity customer, Long shipmentId) throws Exception {
        return accounts.getStatement(customer.getId(), null, null).getLines().stream()
                .filter(line -> shipmentId.equals(line.getProductShipmentId()))
                .findFirst().orElseThrow();
    }

    private com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse catalogRow(
            CustomerEntity customer, ProductShipmentEntity shipment) {
        return catalog.search(null, null, null, customer.getId(), null, null, null, 0).stream()
                .filter(row -> shipment.getId().equals(row.getProductShipmentId()))
                .findFirst().orElseThrow();
    }

    private BigDecimal receivableEstimate(CustomerEntity customer, ProductShipmentEntity shipment) {
        return accounts.searchReceivables(null, null, null, null, null, null, null, null, false, 50).stream()
                .filter(row -> shipment.getId().equals(row.getProductShipmentId()))
                .findFirst().orElseThrow()
                .getEstimatedTotal();
    }
}
