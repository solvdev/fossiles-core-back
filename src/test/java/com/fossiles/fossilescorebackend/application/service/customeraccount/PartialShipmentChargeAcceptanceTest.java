package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.LfPartialReleaseDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfShipmentDocumentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CB116-like order: products Q16,131 across two partial shipments. Each shipment is charged when it is dispatched,
 * for what it carries; the customer owes the sum of the shipments that left.
 */
class PartialShipmentChargeAcceptanceTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release1;
    private ProductionOrderPartialReleaseEntity release2;
    private ProductShipmentEntity shipment1;
    private ProductShipmentEntity shipment2;

    @BeforeEach
    void setUpOrder() {
        customer = fx.customer("CB116-T");
        order = fx.order(customer, "OPV-T0124", TYPE_OPV, "15184.00", "947.00");
        release1 = fx.release(order, 1);
        shipment1 = fx.shipment(order, release1, "ENVP-90124-ENV-00001", "15184.00");
        release2 = fx.release(order, 2);
        shipment2 = fx.shipment(order, release2, "ENVP-90124-ENV-00002", "947.00");
        fx.withStatus(shipment2, "CONFIRMED");
    }

    private void dispatchSecondPartial() throws Exception {
        fx.withStatus(shipment2, "SENT");
        fx.dispatch(shipment2).orElseThrow();
    }

    @Test
    @DisplayName("Only the dispatched partial is owed; the confirmed one adds nothing until it leaves")
    void secondPartialOnlyCountsOnceItIsDispatched() throws Exception {
        CustomerAccountEntryEntity charge1 = fx.dispatch(shipment1).orElseThrow();
        assertThat(fx.dispatch(shipment2)).isEmpty();

        assertThat(charge1.getAmount()).isEqualByComparingTo("15184.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("15184.00");
        assertThat(fx.receivableRows(customer, shipment2)).isEmpty();
        assertThat(fx.receivableRows(customer, shipment1)).singleElement()
                .satisfies(row -> assertThat(row.getChargeEntryId()).isEqualTo(charge1.getId()));

        dispatchSecondPartial();

        assertThat(fx.balance(customer)).isEqualByComparingTo("16131.00");
        assertThat(fx.activeCharges(customer)).hasSize(2)
                .extracting(CustomerAccountEntryEntity::getProductShipmentId)
                .containsExactlyInAnyOrder(shipment1.getId(), shipment2.getId());
        assertThat(fx.receivableRows(customer, shipment2)).singleElement().satisfies(row -> {
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeStatus()).isEqualTo("CHARGED");
            assertThat(row.getChargedAmount()).isEqualByComparingTo("947.00");
        });
    }

    @Test
    @DisplayName("A payment on the first partial stays on it; the later partial adds its own charge to the balance")
    void paymentOnFirstPartialDoesNotTouchTheSecond() throws Exception {
        CustomerAccountEntryEntity charge1 = fx.dispatch(shipment1).orElseThrow();
        fx.create(customer, creditRequest("PAYMENT", charge1.getId(), "5000.00"));
        assertThat(fx.balance(customer)).isEqualByComparingTo("10184.00");

        dispatchSecondPartial();

        assertThat(fx.balance(customer)).isEqualByComparingTo("11131.00");
        assertThat(fx.catalogRow(customer, shipment1).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(fx.catalogRow(customer, shipment2).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.chargeBalance(customer, charge1.getId())).isEqualByComparingTo("10184.00");

        LfSalesDocumentResponse document = lfDocument();
        assertThat(partialDoc(document, release1).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(partialDoc(document, release1).getShipments())
                .extracting(LfShipmentDocumentResponse::getChargeStatus)
                .containsExactly("PARTIAL");
        assertThat(partialDoc(document, release2).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(document.getChargedAmount()).isEqualByComparingTo("16131.00");
        assertThat(document.getBalanceDue()).isEqualByComparingTo("11131.00");

        assertThatThrownBy(() -> fx.create(customer, creditRequest("PAYMENT", charge1.getId(), "10185.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto excede el saldo pendiente del documento (Q 10184.00).");
    }

    @Test
    @DisplayName("Shipping becomes an adjustment on the shipment's own charge and follows later edits")
    void shippingAdjustmentFollowsTheShipment() throws Exception {
        fx.withShipping(shipment1, "75.00");
        CustomerAccountEntryEntity charge = fx.dispatch(shipment1).orElseThrow();

        assertThat(fx.activeAdjustments(customer)).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.getAmount()).isEqualByComparingTo("75.00");
            assertThat(adjustment.getAppliedToEntryId()).isEqualTo(charge.getId());
            assertThat(adjustment.getProductShipmentId()).isEqualTo(shipment1.getId());
        });
        assertThat(fx.balance(customer)).isEqualByComparingTo("15259.00");

        fx.withShipping(shipment1, "90.00");
        fx.accounts.syncShipmentEntries(shipment1.getId());
        assertThat(fx.balance(customer)).isEqualByComparingTo("15274.00");

        fx.withShipping(shipment1, null);
        fx.accounts.syncShipmentEntries(shipment1.getId());
        assertThat(fx.activeAdjustments(customer)).isEmpty();
        assertThat(fx.balance(customer)).isEqualByComparingTo("15184.00");
    }

    @Test
    @DisplayName("Editing a dispatched shipment re-prices its charge, unless payments already exceed the new total")
    void editingTheShipmentUpdatesItsCharge() throws Exception {
        CustomerAccountEntryEntity charge = fx.dispatch(shipment1).orElseThrow();
        fx.setLinePrice(shipment1, "10000.00");
        fx.accounts.syncShipmentEntries(shipment1.getId());

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("10000.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("10000.00");

        fx.create(customer, creditRequest("PAYMENT", charge.getId(), "9000.00"));
        fx.setLinePrice(shipment1, "8000.00");
        assertThatThrownBy(() -> fx.accounts.syncShipmentEntries(shipment1.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pagos o créditos aplicados");
        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("Cancelling or reverting a dispatch voids its charge and shipping; with payments it is refused")
    void reversingTheDispatchVoidsTheCharge() throws Exception {
        fx.withShipping(shipment1, "75.00");
        CustomerAccountEntryEntity charge1 = fx.dispatch(shipment1).orElseThrow();
        dispatchSecondPartial();
        assertThat(fx.balance(customer)).isEqualByComparingTo("16206.00");

        fx.accounts.voidEntriesForShipment(shipment1.getId(), "Envío anulado");

        assertThat(fx.entry(charge1.getId()).getStatus()).isEqualTo("VOID");
        assertThat(fx.activeAdjustments(customer)).isEmpty();
        assertThat(fx.activeCharges(customer)).singleElement()
                .extracting(CustomerAccountEntryEntity::getProductShipmentId).isEqualTo(shipment2.getId());
        assertThat(fx.balance(customer)).isEqualByComparingTo("947.00");

        CustomerAccountEntryEntity charge2 = fx.activeCharges(customer).get(0);
        fx.create(customer, creditRequest("PAYMENT", charge2.getId(), "100.00"));
        assertThatThrownBy(() -> fx.accounts.voidEntriesForShipment(shipment2.getId(), "Envío anulado"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pagos, notas de crédito o devoluciones");
        assertThat(fx.entry(charge2.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(fx.balance(customer)).isEqualByComparingTo("847.00");
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), true).stream()
                .filter(doc -> doc.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static LfPartialReleaseDocumentResponse partialDoc(
            LfSalesDocumentResponse document, ProductionOrderPartialReleaseEntity release) {
        return document.getPartialReleases().stream()
                .filter(partial -> release.getId().equals(partial.getPartialReleaseId()))
                .findFirst()
                .orElseThrow();
    }
}
