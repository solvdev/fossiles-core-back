package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
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

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order total cap. Products sum to Q1,500 (partial 1 Q1,000, partial 2 Q500) and the order declares Q50 shipping
 * ({@code __OPV_SHIPPING__}), so the target total is Q1,550. createEntry stores the request amount as-is; nothing
 * compares it with a shipment, partial or order estimate.
 */
class OrderTotalCapCharacterizationTest extends LfReceivablesH2TestBase {

    @Autowired
    private ProductionOrderRepository orderRepository;

    @Autowired
    private ProductShipmentRepository shipmentRepository;

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release1;
    private ProductionOrderPartialReleaseEntity release2;
    private ProductShipmentEntity shipment1;
    private ProductShipmentEntity shipment2;

    @BeforeEach
    void setUpOrder() {
        customer = fx.customer("CT001-T");
        order = fx.order(customer, "OPV-T0600", TYPE_OPV, "1000.00", "500.00");
        order.setObservations("__OPV_SHIPPING__:50.00");
        orderRepository.save(order);
        release1 = fx.release(order, 1);
        release2 = fx.release(order, 2);
        shipment1 = fx.shipment(order, release1, "ENVP-90600-ENV-00001", "1000.00");
        shipment2 = fx.shipment(order, release2, "ENVP-90600-ENV-00002", "500.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): per-shipment charges of Q1,400 + Q900 on a Q1,550 order are accepted "
            + "(active total Q2,300) — fix should reject partial charges and any charge pushing the order's active "
            + "total above products + shipping")
    void perShipmentChargesAboveOrderTotalAreAccepted() throws Exception {
        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1550.00");

        fx.charge(customer, order, release1, shipment1, "1400.00");
        fx.charge(customer, order, release2, shipment2, "900.00");

        assertThat(fx.activeCharges(customer).stream()
                .map(CustomerAccountEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2300.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): an order-level charge of Q5,000 on a Q1,550 order is saved as sent — fix "
            + "should reject charges above products + shipping")
    void orderLevelChargeAboveOrderTotalIsAccepted() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "5000.00");

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("5000.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: shipping per path — order estimate adds the order's __OPV_SHIPPING__ only; the "
            + "shipments list adds it to partial 1 only and ignores product_shipment.shipping_cost; cartera uses "
            + "product_shipment.shipping_cost when set (any partial), else the order's shipping on partial 1")
    void shippingIncludedPerEstimatePath() {
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1050.00");
        assertThat(fx.receivableRows(customer, shipment1)).singleElement()
                .satisfies(r -> assertThat(r.getEstimatedTotal()).isEqualByComparingTo("1050.00"));

        shipment1.setShippingCost(new BigDecimal("75.00"));
        shipment2.setShippingCost(new BigDecimal("75.00"));
        shipmentRepository.save(shipment1);
        shipmentRepository.save(shipment2);

        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1550.00");
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1050.00");
        assertThat(fx.catalogRow(customer, shipment2).getEstimatedTotal()).isEqualByComparingTo("500.00");
        assertThat(fx.receivableRows(customer, shipment1)).singleElement()
                .satisfies(r -> assertThat(r.getEstimatedTotal()).isEqualByComparingTo("1075.00"));
        assertThat(fx.receivableRows(customer, shipment2)).singleElement()
                .satisfies(r -> assertThat(r.getEstimatedTotal()).isEqualByComparingTo("575.00"));
    }
}
