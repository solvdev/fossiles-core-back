package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Objects;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order total cap. Products sum to Q1,500; partial 1 (Q1,000) and partial 2 (Q500) have real shipments with
 * shipping Q75 and Q40; partial 3 is planned, with no shipment record. Target charge amount = products + shipping
 * of the shipments that exist = Q1,615. createEntry stores the request amount as-is and compares it with nothing.
 */
class OrderTotalCapCharacterizationTest extends LfReceivablesH2TestBase {

    private static final String EXPECTED_ORDER_TOTAL = "1615.00";

    @Autowired
    private ProductionOrderRepository orderRepository;

    @Autowired
    private ProductShipmentRepository shipmentRepository;

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release1;
    private ProductionOrderPartialReleaseEntity release2;
    private ProductionOrderPartialReleaseEntity plannedRelease;
    private ProductShipmentEntity shipment1;
    private ProductShipmentEntity shipment2;

    @BeforeEach
    void setUpOrder() {
        customer = fx.customer("CT001-T");
        order = fx.order(customer, "OPV-T0600", TYPE_OPV, "1000.00", "500.00");
        release1 = fx.release(order, 1);
        release2 = fx.release(order, 2);
        plannedRelease = fx.release(order, 3);
        shipment1 = withShipping(fx.shipment(order, release1, "ENVP-90600-ENV-00001", "1000.00"), "75.00");
        shipment2 = withShipping(fx.shipment(order, release2, "ENVP-90600-ENV-00002", "500.00"), "40.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): per-shipment charges of Q1,400 + Q900 on a Q1,615 order are accepted "
            + "(active total Q2,300) — fix should reject partial charges and any charge pushing the order's active "
            + "total above products + shipping of existing shipments")
    void perShipmentChargesAboveOrderTotalAreAccepted() throws Exception {
        fx.charge(customer, order, release1, shipment1, "1400.00");
        fx.charge(customer, order, release2, shipment2, "900.00");

        assertThat(fx.activeCharges(customer).stream()
                .map(CustomerAccountEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2300.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): an order-level charge of Q5,000 on a Q1,615 order is saved as sent — fix "
            + "should reject charges above products + shipping of existing shipments")
    void orderLevelChargeAboveOrderTotalIsAccepted() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "5000.00");

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("5000.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): with two real partial shipments carrying shipping, the shipments list and the "
            + "order estimate ignore product_shipment.shipping_cost (Q1,000 + Q500, order Q1,500); cartera and "
            + "/lf-documents add it (Q1,075 + Q540 = Q1,615) — fix: every path should total Q1,615")
    void shippingOfExistingPartialShipmentsPerPath() throws Exception {
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1000.00");
        assertThat(fx.catalogRow(customer, shipment2).getEstimatedTotal()).isEqualByComparingTo("500.00");
        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1500.00");

        assertThat(receivableEstimate(shipment1)).isEqualByComparingTo("1075.00");
        assertThat(receivableEstimate(shipment2)).isEqualByComparingTo("540.00");
        assertThat(lfDocument().getEstimatedTotal()).isEqualByComparingTo(EXPECTED_ORDER_TOTAL);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): a planned partial with no shipment record shows the whole-order estimate in "
            + "cartera (products + the order's __OPV_SHIPPING__ = Q1,550); /lf-documents gives it no estimate and the "
            + "shipments list has no row for it — fix: a planned partial adds no shipping")
    void plannedPartialWithoutShipment() throws Exception {
        order.setObservations("__OPV_SHIPPING__:50.00");
        orderRepository.save(order);

        CustomerAccountReceivableSearchResponse plannedRow = fx.receivableRows(customer).stream()
                .filter(r -> Objects.equals(r.getPartialReleaseId(), plannedRelease.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(plannedRow.getProductShipmentId()).isNull();
        assertThat(plannedRow.getEstimatedTotal()).isEqualByComparingTo("1550.00");

        assertThat(lfDocument().getPartialReleases())
                .filteredOn(p -> Objects.equals(p.getPartialReleaseId(), plannedRelease.getId()))
                .singleElement()
                .satisfies(p -> assertThat(p.getEstimatedTotal()).isNull());

        assertThat(fx.catalogRows(customer))
                .noneMatch(r -> Objects.equals(r.getPartialReleaseId(), plannedRelease.getId()));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: the order's __OPV_SHIPPING__ is added to the order estimate and to partial 1 in "
            + "the shipments list; cartera prefers product_shipment.shipping_cost when the shipment has one")
    void orderLevelShippingTagPerPath() {
        order.setObservations("__OPV_SHIPPING__:50.00");
        orderRepository.save(order);

        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1550.00");
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1050.00");
        assertThat(fx.catalogRow(customer, shipment2).getEstimatedTotal()).isEqualByComparingTo("500.00");
        assertThat(receivableEstimate(shipment1)).isEqualByComparingTo("1075.00");
        assertThat(receivableEstimate(shipment2)).isEqualByComparingTo("540.00");
    }

    private ProductShipmentEntity withShipping(ProductShipmentEntity shipment, String shippingCost) {
        shipment.setShippingCost(new BigDecimal(shippingCost));
        return shipmentRepository.save(shipment);
    }

    private BigDecimal receivableEstimate(ProductShipmentEntity shipment) {
        return fx.receivableRows(customer, shipment).stream()
                .map(CustomerAccountReceivableSearchResponse::getEstimatedTotal)
                .findFirst()
                .orElseThrow();
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), false).stream()
                .filter(d -> d.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }
}
