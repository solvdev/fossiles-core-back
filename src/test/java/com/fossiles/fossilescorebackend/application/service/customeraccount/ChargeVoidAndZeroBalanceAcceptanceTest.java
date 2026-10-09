package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_ORDER_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.PARTIAL_HAS_NO_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.VOID_BLOCKED;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A settled customer can be charged for a new order. A charge with a payment cannot be voided. */
class ChargeVoidAndZeroBalanceAcceptanceTest extends LfReceivablesH2TestBase {

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
        settledCharge = fx.charge(customer, order, null, null, "1968.00");
        payment = fx.create(customer, creditRequest("PAYMENT", settledCharge.getId(), "1968.00"));
    }

    @Test
    @DisplayName("A new order for a zero-balance customer can be charged")
    void newShipmentForZeroBalanceCustomerIsAccepted() throws Exception {
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        ProductionOrderEntity newOrder = fx.order(customer, "OPC-T0301", TYPE_OPC, "2500.00");
        ProductionOrderPartialReleaseEntity newRelease = fx.release(newOrder, 1);
        ProductShipmentEntity newShipment = fx.shipment(newOrder, newRelease, "ENVP-90301-ENV-00001", "2500.00");

        assertThat(fx.catalogRow(customer, newShipment).isHasCharge()).isFalse();
        fx.charge(customer, newOrder, null, null, "2500.00");

        assertThat(fx.catalogRow(customer, newShipment).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.catalogRow(customer, newShipment).isHasCharge()).isTrue();
        assertThat(fx.balance(customer)).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("Voiding a paid charge is blocked and the payment stays applied")
    void voidAndRecreateOrphansThePayment() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE)
                .hasMessageNotContaining("Anúlelo")
                .hasMessageNotContaining("registrarlo de nuevo");
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE)
                .hasMessageNotContaining("Anúlelo")
                .hasMessageNotContaining("registrarlo de nuevo");

        CustomerAccountEntryVoidRequest voidRequest = new CustomerAccountEntryVoidRequest();
        voidRequest.setVoidReason("Re-registrar cargo");
        assertThatThrownBy(() -> fx.accounts.voidEntry(settledCharge.getId(), voidRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage(VOID_BLOCKED)
                .hasMessageNotContaining("Anúlelo")
                .hasMessageNotContaining("registrarlo de nuevo");

        assertThat(fx.entry(settledCharge.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(fx.entry(payment.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(fx.entry(payment.getId()).getAppliedToEntryId()).isEqualTo(settledCharge.getId());
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }
}
