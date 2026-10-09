package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Objects;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.PARTIAL_HAS_NO_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Products Q1,500. Existing shipments carry shipping Q75 and Q40, so the cap is Q1,615.
 * The stored charge is the product total Q1,500. A planned partial with no shipment adds nothing.
 */
class OrderTotalCapAcceptanceTest extends LfReceivablesH2TestBase {

    @Autowired
    private ProductionOrderRepository orderRepository;

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
        shipment1 = fx.withShipping(fx.shipment(order, release1, "ENVP-90600-ENV-00001", "1000.00"), "75.00");
        shipment2 = fx.withShipping(fx.shipment(order, release2, "ENVP-90600-ENV-00002", "500.00"), "40.00");
    }

    @Test
    @DisplayName("Partial charges are rejected and a charge above products plus existing shipping is rejected")
    void perShipmentChargesAboveOrderTotalAreAccepted() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "1400.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "900.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "5000.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto supera el total de la orden (Q 1615.00). El cargo se calcula en el servidor.");

        var charge = fx.charge(customer, order, null, null, "1615.00");
        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("1500.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("1500.00");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("An order-level charge above products plus existing shipping is rejected")
    void orderLevelChargeAboveOrderTotalIsAccepted() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "5000.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("supera el total de la orden (Q 1615.00)");
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.activeCharges(customer)).isEmpty();
    }

    @Test
    @DisplayName("Catalog, cartera and lf-documents use product_shipment.shipping_cost")
    void shippingOfExistingPartialShipmentsPerPath() throws Exception {
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1075.00");
        assertThat(fx.catalogRow(customer, shipment2).getEstimatedTotal()).isEqualByComparingTo("540.00");
        assertThat(receivableEstimate(shipment1)).isEqualByComparingTo("1075.00");
        assertThat(receivableEstimate(shipment2)).isEqualByComparingTo("540.00");
        assertThat(lfDocument().getEstimatedTotal()).isEqualByComparingTo("1615.00");
    }

    @Test
    @DisplayName("PENDING_EDUARDO: estimateVendorOrderTotal still ignores product_shipment.shipping_cost")
    void estimateVendorOrderTotalStillIgnoresShipmentShipping_PENDING_EDUARDO() {
        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("A planned partial with no shipment has a null estimate and no catalog row")
    void plannedPartialWithoutShipment() throws Exception {
        order.setObservations("__OPV_SHIPPING__:50.00");
        orderRepository.save(order);

        CustomerAccountReceivableSearchResponse plannedRow = fx.receivableRows(customer).stream()
                .filter(row -> Objects.equals(row.getPartialReleaseId(), plannedRelease.getId()))
                .filter(row -> row.getProductShipmentId() == null)
                .findFirst()
                .orElseThrow();
        assertThat(plannedRow.getEstimatedTotal()).isNull();
        assertThat(lfDocument().getPartialReleases())
                .filteredOn(partial -> Objects.equals(partial.getPartialReleaseId(), plannedRelease.getId()))
                .singleElement()
                .satisfies(partial -> assertThat(partial.getEstimatedTotal()).isNull());
        assertThat(fx.catalogRows(customer))
                .noneMatch(row -> Objects.equals(row.getPartialReleaseId(), plannedRelease.getId()));
    }

    @Test
    @DisplayName("Catalog and cartera use the shipment shipping cost; the order estimate still reads __OPV_SHIPPING__")
    void orderLevelShippingTagPerPath() {
        order.setObservations("__OPV_SHIPPING__:50.00");
        orderRepository.save(order);

        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1550.00");
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("1075.00");
        assertThat(fx.catalogRow(customer, shipment2).getEstimatedTotal()).isEqualByComparingTo("540.00");
        assertThat(receivableEstimate(shipment1)).isEqualByComparingTo("1075.00");
        assertThat(receivableEstimate(shipment2)).isEqualByComparingTo("540.00");
    }

    private BigDecimal receivableEstimate(ProductShipmentEntity shipment) {
        return fx.receivableRows(customer, shipment).stream()
                .map(CustomerAccountReceivableSearchResponse::getEstimatedTotal)
                .findFirst()
                .orElseThrow();
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), false).stream()
                .filter(doc -> doc.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }
}
