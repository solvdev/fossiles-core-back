package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfShipmentDocumentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.CHARGE_REQUIRES_ORDER;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_ORDER_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.PARTIAL_HAS_NO_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.chargeRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.legacyCharge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CA502-like order: one active charge for the production order, stored as the product total.
 * Read models report that charge on every shipment. COVERED is not a status.
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
    @DisplayName("Order charge stores the product total, a full payment is PAID, and a second charge is rejected")
    void exactShipmentCharge_settled_isReportedPaidAndBlocksSecondCharge() throws Exception {
        CustomerAccountEntryResponse charge = orderCharge("1783.00");
        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("1968.00");
        pay(charge.getId(), "1000.00");
        pay(charge.getId(), "968.00");

        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.catalogRow(customer, shipment).getEstimatedTotal()).isEqualByComparingTo("1968.00");
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
            assertThat(row.getChargeStatus()).isEqualTo("PAID");
            assertThat(row.getChargedAmount()).isEqualByComparingTo("1968.00");
            assertThat(row.isHasCharge()).isTrue();
        });
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PAID");
        assertNoCovered();
        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> orderCharge("1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("An order charge below the product total is stored as the product total and stays PARTIAL until paid")
    void orderLevelChargeBelowEstimate_catalogUncharged_secondRejected() throws Exception {
        CustomerAccountEntryResponse charge = orderCharge("1783.00");
        pay(charge.getId(), "1000.00");
        pay(charge.getId(), "783.00");

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("1968.00");
        assertThat(fx.entry(charge.getId()).getPartialReleaseId()).isNull();
        assertThat(fx.entry(charge.getId()).getProductShipmentId()).isNull();
        assertThat(fx.balance(customer)).isEqualByComparingTo("185.00");
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeStatus()).isEqualTo("PARTIAL");
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
        });
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(lfDocument().getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(lfDocument().getChargeEntryId()).isEqualTo(charge.getId());
        assertNoCovered();
        assertThatThrownBy(() -> orderCharge("1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("A charge tied to a partial release is rejected; the order charge covers the shipment")
    void partialReleaseChargeWithNullShipment_catalogUncharged_secondRejected() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, release, null, "1783.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        CustomerAccountEntryResponse charge = orderCharge("1968.00");
        pay(charge.getId(), "1968.00");

        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment)).singleElement()
                .extracting(CustomerAccountReceivableSearchResponse::getChargeStatus)
                .isEqualTo("PAID");
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PAID");
        assertNoCovered();
    }

    @Test
    @DisplayName("A charge on a sibling partial is rejected; one order charge covers the real shipment")
    void siblingPartialFullyCharged_coveredWithoutOverlap_secondChargeAccepted() throws Exception {
        ProductionOrderPartialReleaseEntity sibling = fx.release(order, 2);
        assertThatThrownBy(() -> fx.charge(customer, order, sibling, null, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        CustomerAccountEntryResponse charge = orderCharge("1968.00");
        pay(charge.getId(), "1968.00");

        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment)).isNotEmpty();
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PAID");
        assertThat(lfDocument().getChargeEntryId()).isEqualTo(charge.getId());
        assertThat(lfDocument().getChargeStatus()).isEqualTo("PAID");
        assertNoCovered();
        assertThatThrownBy(() -> orderCharge("1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("A charge with only the parent ENVP number and no production order is rejected")
    void vendorShipmentNumberOnlyCharge_isInvisibleAndAllowsDuplicate() {
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
    @DisplayName("A CHARGE without productionOrderId is rejected")
    void chargeWithoutProductionOrderIsAccepted() {
        assertThatThrownBy(() -> fx.create(customer, chargeRequest(null, null, null, "1783.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CHARGE_REQUIRES_ORDER);
        assertThat(fx.activeCharges(customer)).isEmpty();
    }

    @Test
    @DisplayName("A charge that names a shipment is rejected; the order charge is visible on that shipment")
    void shipmentChargeWithoutPartialRelease_listAndGuardDisagree() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, null, shipment, "1783.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        CustomerAccountEntryResponse charge = orderCharge("1968.00");
        pay(charge.getId(), "1968.00");

        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
            assertThat(row.getDocumentLevel()).isEqualTo("SHIPMENT");
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
            assertThat(row.getChargeStatus()).isNotEqualTo("NONE");
        });
        assertNoCovered();
    }

    @Test
    @DisplayName("Client amount 0 is stored as the product total; a legacy Q0 charge still blocks another charge")
    void zeroAmountCharge() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "0.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        ProductionOrderEntity other = fx.order(customer, "OPC-T0029", TYPE_OPC, "500.00");
        ProductionOrderPartialReleaseEntity otherRelease = fx.release(other, 1);
        ProductShipmentEntity otherShipment = fx.shipment(other, otherRelease, "ENVP-90029-ENV-00001", "500.00");
        CustomerAccountEntryResponse stored = fx.charge(customer, other, null, null, "0.00");
        assertThat(fx.entry(stored.getId()).getAmount()).isEqualByComparingTo("500.00");

        fx.insert(legacyCharge(customer, order, null, null, "0.00").build());
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment))
                .extracting(CustomerAccountReceivableSearchResponse::getChargeStatus)
                .contains("PAID");
        assertThatThrownBy(() -> orderCharge("1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
    }

    @Test
    @DisplayName("Two legacy charges do not break the catalog; the earliest one is the order charge and another is rejected")
    void twoActiveChargesOnOneShipment() {
        Long firstId = fx.insert(legacyCharge(customer, order, release, shipment, "1783.00").build()).getId();
        Long secondId = fx.insert(legacyCharge(customer, order, release, shipment, "1783.00").build()).getId();
        Long earliest = Math.min(firstId, secondId);

        assertThatCode(() -> fx.catalogRows(customer)).doesNotThrowAnyException();
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("CHARGED");
        assertThatCode(() -> fx.receivableRows(customer, shipment)).doesNotThrowAnyException();
        assertThat(fx.receivableRows(customer, shipment))
                .extracting(CustomerAccountReceivableSearchResponse::getChargeEntryId)
                .contains(earliest);
        assertThatThrownBy(() -> orderCharge("1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
    }

    private CustomerAccountEntryResponse orderCharge(String clientAmount) throws Exception {
        return fx.charge(customer, order, null, null, clientAmount);
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
