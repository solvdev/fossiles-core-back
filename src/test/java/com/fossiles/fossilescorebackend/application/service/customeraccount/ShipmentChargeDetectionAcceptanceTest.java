package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
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

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.CHARGE_NEEDS_SHIPMENT;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.CHARGE_REQUIRES_ORDER;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_SHIPMENT_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.ORDER_ALREADY_CHARGED;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.SHIPMENT_NOT_DISPATCHED;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.chargeRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CA502-like order: the charge is born when the shipment is dispatched (SENT/DELIVERED), one per shipment,
 * for what the shipment carries. Read models report that charge on the shipment. COVERED is not a status.
 */
class ShipmentChargeDetectionAcceptanceTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release;
    private ProductShipmentEntity shipment;

    @BeforeEach
    void setUpShipment() {
        customer = fx.customer("CA502-T");
        order = fx.order(customer, "OPC-T0028", TYPE_OPC, "1968.00");
        release = fx.release(order, 1);
        shipment = fx.shipment(order, release, "ENVP-90028-ENV-00001", "1968.00");
    }

    @Test
    @DisplayName("Dispatch stores the shipment total, a full payment is PAID, and dispatching again adds nothing")
    void dispatchCharge_settled_isReportedPaidAndIsIdempotent() throws Exception {
        CustomerAccountEntryEntity charge = fx.dispatch(shipment).orElseThrow();
        assertThat(charge.getAmount()).isEqualByComparingTo("1968.00");
        assertThat(charge.getProductShipmentId()).isEqualTo(shipment.getId());
        assertThat(charge.getPartialReleaseId()).isEqualTo(release.getId());
        pay(charge.getId(), "1000.00");
        pay(charge.getId(), "968.00");

        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
            assertThat(row.getChargeStatus()).isEqualTo("PAID");
            assertThat(row.getChargedAmount()).isEqualByComparingTo("1968.00");
            assertThat(row.isHasCharge()).isTrue();
        });
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PAID");
        assertNoCovered();
        assertThat(fx.dispatch(shipment)).isEmpty();
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("A partly paid dispatch charge stays PARTIAL until paid")
    void partlyPaidDispatchCharge() throws Exception {
        CustomerAccountEntryEntity charge = fx.dispatch(shipment).orElseThrow();
        pay(charge.getId(), "1000.00");
        pay(charge.getId(), "783.00");

        assertThat(fx.balance(customer)).isEqualByComparingTo("185.00");
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeStatus()).isEqualTo("PARTIAL");
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
        });
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PARTIAL");
        assertNoCovered();
    }

    @Test
    @DisplayName("A shipment that has not left (draft, confirmed, cancelled) has no charge and no cartera row")
    void undispatchedShipmentHasNoChargeAndNoCarteraRow() throws Exception {
        for (String status : new String[] {"DRAFT", "CONFIRMED", "CANCELLED"}) {
            fx.withStatus(shipment, status);

            assertThat(fx.dispatch(shipment)).isEmpty();
            assertThat(fx.receivableRows(customer, shipment)).isEmpty();
            assertThatThrownBy(() -> fx.create(customer, chargeRequest(order, release, shipment, "1968.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(SHIPMENT_NOT_DISPATCHED + status);
        }
        assertThat(fx.activeCharges(customer)).isEmpty();
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Manual retry for a dispatched shipment ignores the client amount and rejects a second charge")
    void manualRetryUsesServerAmountAndRejectsDuplicate() throws Exception {
        CustomerAccountEntryResponse charge = fx.create(customer, chargeRequest(order, release, shipment, "1.00"));

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("1968.00");
        assertThat(fx.entry(charge.getId()).getProductShipmentId()).isEqualTo(shipment.getId());
        assertThatThrownBy(() -> fx.create(customer, chargeRequest(order, release, shipment, "1968.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_SHIPMENT_CHARGE);
        assertThat(fx.dispatch(shipment)).isEmpty();
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("A charge for the whole order, or without an order, is rejected")
    void orderLevelAndOrderlessChargesAreRejected() {
        assertThatThrownBy(() -> fx.create(customer, chargeRequest(order, null, null, "1968.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CHARGE_NEEDS_SHIPMENT);
        CustomerAccountEntryRequest vendorOnly = chargeRequest(null, null, null, "1783.00");
        vendorOnly.setVendorShipmentNumber(order.getVendorShipmentNumber());
        assertThatThrownBy(() -> fx.create(customer, vendorOnly))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CHARGE_REQUIRES_ORDER);
        assertThat(fx.activeCharges(customer)).isEmpty();
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isFalse();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("A dispatched shipment without a charge shows up in cartera as NONE")
    void dispatchedShipmentWithoutChargeIsVisibleAsNone() {
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.getChargeStatus()).isEqualTo("NONE");
            assertThat(row.isHasCharge()).isFalse();
            assertThat(row.getBalanceDue()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("An order that already has a whole-order charge gets no second charge per shipment")
    void legacyOrderChargeBlocksShipmentCharges() throws Exception {
        fx.charge(customer, order, null, null, "1968.00");

        assertThat(fx.dispatch(shipment)).isEmpty();
        assertThatThrownBy(() -> fx.create(customer, chargeRequest(order, release, shipment, "1968.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ORDER_ALREADY_CHARGED);
        assertThat(fx.activeCharges(customer)).hasSize(1);
        assertThat(fx.balance(customer)).isEqualByComparingTo("1968.00");
    }

    @Test
    @DisplayName("A whole-order charge only shows what was dispatched; the rest is not owed yet")
    void legacyOrderChargeIsClippedToWhatWasDispatched() throws Exception {
        ProductionOrderEntity big = fx.order(customer, "OPC-T0030", TYPE_OPC, "1000.00", "968.00");
        ProductShipmentEntity first = fx.shipment(big, fx.release(big, 1), "ENVP-90030-ENV-00001", "1000.00");
        ProductShipmentEntity second = fx.shipment(big, fx.release(big, 2), "ENVP-90030-ENV-00002", "968.00");
        fx.withStatus(second, "CONFIRMED");
        CustomerAccountEntryResponse charge = fx.charge(customer, big, null, null, "1968.00");

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("1968.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("1000.00");
        assertThat(fx.receivableRows(customer, second)).isEmpty();
        assertThat(fx.receivableRows(customer, first)).singleElement()
                .extracting(CustomerAccountReceivableSearchResponse::getChargedAmount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("1000.00"));

        fx.withStatus(second, "SENT");
        assertThat(fx.balance(customer)).isEqualByComparingTo("1968.00");
    }

    private void pay(Long chargeId, String amount) throws Exception {
        fx.create(customer, creditRequest("PAYMENT", chargeId, amount));
    }

    private void assertNoCovered() throws Exception {
        assertThat(lfDocument().getChargeStatus()).isNotEqualTo("COVERED");
        assertThat(lfDocument().getPartialReleases())
                .allSatisfy(partial -> {
                    assertThat(partial.getChargeStatus()).isNotEqualTo("COVERED");
                    assertThat(partial.getShipments())
                            .extracting(LfShipmentDocumentResponse::getChargeStatus)
                            .doesNotContain("COVERED");
                });
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), true).stream()
                .filter(doc -> doc.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }

    private LfShipmentDocumentResponse shipmentDoc() throws Exception {
        return lfDocument().getPartialReleases().stream()
                .flatMap(partial -> partial.getShipments().stream())
                .filter(doc -> shipment.getId().equals(doc.getProductShipmentId()))
                .findFirst()
                .orElseThrow();
    }
}
