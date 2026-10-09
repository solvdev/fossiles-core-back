package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountBalanceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CA223: a credit linked to the OPV charge keeps that charge's order and kind, even when the request names the OPC order.
 */
class CrossKindPaymentAcceptanceTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity opcOrder;
    private ProductionOrderEntity opvOrder;
    private CustomerAccountEntryResponse opcCharge;
    private CustomerAccountEntryResponse opvCharge;

    @BeforeEach
    void setUpCharges() throws Exception {
        customer = fx.customer("CA223-T");
        opcOrder = fx.order(customer, "OPC-T0223", TYPE_OPC, "2850.00");
        opvOrder = fx.order(customer, "OPV-T0224", TYPE_OPV, "5712.00");
        opcCharge = fx.charge(customer, opcOrder, null, null, "2850.00");
        opvCharge = fx.charge(customer, opvOrder, null, null, "5712.00");
    }

    @Test
    @DisplayName("Payment, credit note and return linked to the OPV charge stay OPV when the request sends the OPC order")
    void creditsLinkedToOpvChargeWithOpcOrderAreBookedAsOpc() throws Exception {
        CustomerAccountEntryEntity payment = stored(
                withOrder(creditRequest("PAYMENT", opvCharge.getId(), "1000.00"), opcOrder));
        CustomerAccountEntryEntity creditNote = stored(
                withOrder(creditRequest("CREDIT_NOTE", opvCharge.getId(), "500.00"), opcOrder));
        CustomerAccountEntryEntity returned = stored(
                withOrder(creditRequest("RETURN", opvCharge.getId(), "200.00"), opcOrder));

        for (CustomerAccountEntryEntity credit : new CustomerAccountEntryEntity[] {payment, creditNote, returned}) {
            assertThat(credit.getAppliedToEntryId()).isEqualTo(opvCharge.getId());
            assertThat(credit.getOrderKind()).isEqualTo("OPV");
            assertThat(credit.getProductionOrderId()).isEqualTo(opvOrder.getId());
            assertThat(fx.statementLine(customer, credit.getId()).getOrderKind()).isEqualTo("OPV");
        }

        assertThat(fx.chargeBalance(customer, opvCharge.getId())).isEqualByComparingTo("4012.00");
        assertThat(fx.chargeBalance(customer, opcCharge.getId())).isEqualByComparingTo("2850.00");
        CustomerAccountBalanceResponse balance = fx.accounts.getBalance(customer.getId());
        assertThat(balance.getBalance()).isEqualByComparingTo("6862.00");
        assertThat(balance.getBalanceDueOpv()).isEqualByComparingTo("4012.00");
        assertThat(balance.getBalanceDueOpc()).isEqualByComparingTo("2850.00");
    }

    @Test
    @DisplayName("A payment linked to the OPV charge with no order of its own stays on that charge")
    void creditWithoutOrderFallsBackToLinkedCharge() throws Exception {
        CustomerAccountEntryEntity payment = stored(creditRequest("PAYMENT", opvCharge.getId(), "1000.00"));

        assertThat(payment.getOrderKind()).isEqualTo("OPV");
        assertThat(payment.getProductionOrderId()).isEqualTo(opvOrder.getId());
        assertThat(fx.statementLine(customer, payment.getId()).getOrderKind()).isEqualTo("OPV");
        CustomerAccountBalanceResponse balance = fx.accounts.getBalance(customer.getId());
        assertThat(balance.getBalanceDueOpv()).isEqualByComparingTo("4712.00");
        assertThat(balance.getBalanceDueOpc()).isEqualByComparingTo("2850.00");
    }

    private CustomerAccountEntryEntity stored(CustomerAccountEntryRequest request) throws Exception {
        return fx.entry(fx.create(customer, request).getId());
    }

    private static CustomerAccountEntryRequest withOrder(CustomerAccountEntryRequest request, ProductionOrderEntity order) {
        request.setProductionOrderId(order.getId());
        return request;
    }
}
