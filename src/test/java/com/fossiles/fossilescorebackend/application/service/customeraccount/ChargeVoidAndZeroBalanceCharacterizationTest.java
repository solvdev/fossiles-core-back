package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Cases 3 and 4: a zero-balance customer can still be charged; void-and-recreate leaves the payment behind. */
class ChargeVoidAndZeroBalanceCharacterizationTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release;
    private ProductShipmentEntity shipment;
    private CustomerAccountEntryResponse settledCharge;
    private CustomerAccountEntryResponse payment;

    @BeforeEach
    void setUpSettledCharge() throws Exception {
        customer = fx.customer("CZ001-T");
        order = fx.order(customer, "OPC-T0300", TYPE_OPC, "1968.00");
        release = fx.release(order, 1);
        shipment = fx.shipment(order, release, "ENVP-90300-ENV-00001", "1968.00");
        settledCharge = fx.charge(customer, order, release, shipment, "1783.00");
        payment = fx.create(customer, creditRequest("PAYMENT", settledCharge.getId(), "1783.00"));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (must stay): a new sale on a new shipment for a zero-balance customer is charged")
    void newShipmentForZeroBalanceCustomerIsAccepted() throws Exception {
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        ProductionOrderEntity newOrder = fx.order(customer, "OPC-T0301", TYPE_OPC, "2500.00");
        ProductionOrderPartialReleaseEntity newRelease = fx.release(newOrder, 1);
        ProductShipmentEntity newShipment = fx.shipment(newOrder, newRelease, "ENVP-90301-ENV-00001", "2500.00");

        assertThat(fx.catalogRow(customer, newShipment).isHasCharge()).isFalse();
        fx.charge(customer, newOrder, newRelease, newShipment, "2500.00");

        assertThat(fx.catalogRow(customer, newShipment).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.balance(customer)).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): voiding a paid charge keeps its payment ACTIVE; the re-created charge "
            + "shows its full Q1,783 open while the customer balance is Q0 — fix should not orphan the payment")
    void voidAndRecreateOrphansThePayment() throws Exception {
        CustomerAccountEntryVoidRequest voidRequest = new CustomerAccountEntryVoidRequest();
        voidRequest.setVoidReason("Re-registrar cargo");
        fx.accounts.voidEntry(settledCharge.getId(), voidRequest);

        assertThat(fx.entry(payment.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(fx.entry(payment.getId()).getAppliedToEntryId()).isEqualTo(settledCharge.getId());
        OpvShipmentCatalogRowResponse afterVoid = fx.catalogRow(customer, shipment);
        assertThat(afterVoid.isHasCharge()).isFalse();
        assertThat(afterVoid.getChargeStatus()).isEqualTo("NONE");
        assertThat(fx.balance(customer)).isEqualByComparingTo("-1783.00");

        CustomerAccountEntryResponse recreated = fx.charge(customer, order, release, shipment, "1783.00");

        assertThat(fx.chargeBalance(customer, recreated.getId())).isEqualByComparingTo("1783.00");
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.accounts.getReceivableDocuments(customer.getId(), null))
                .singleElement()
                .satisfies(doc -> {
                    assertThat(doc.getChargeEntryId()).isEqualTo(recreated.getId());
                    assertThat(doc.getBalanceDue()).isEqualByComparingTo("1783.00");
                });
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
    }
}
