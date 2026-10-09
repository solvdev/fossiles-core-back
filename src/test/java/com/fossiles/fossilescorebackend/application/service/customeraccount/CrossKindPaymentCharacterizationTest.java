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

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CA223-like: OPC charge Q2,850 and OPV charge Q5,712. createEntry takes order_kind and production_order_id from the
 * request's order first and only falls back to the linked charge; the OPV/OPC split reads them back through
 * {@code resolveEntryOrderKind}, while per-charge balances follow applied_to_entry_id.
 */
class CrossKindPaymentCharacterizationTest extends LfReceivablesH2TestBase {

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
    @DisplayName("CURRENT BEHAVIOR (bug): payment and credit note linked to the OPV charge but sent with the OPC order "
            + "are stored as OPC on the OPC order; they reduce the OPV charge but the OPC split — fix should take "
            + "order_kind and production_order_id only from the linked charge")
    void creditsLinkedToOpvChargeWithOpcOrderAreBookedAsOpc() throws Exception {
        CustomerAccountEntryEntity payment = fx.entry(
                fx.create(customer, withOrder(creditRequest("PAYMENT", opvCharge.getId(), "1000.00"), opcOrder)).getId());
        CustomerAccountEntryEntity creditNote = fx.entry(
                fx.create(customer, withOrder(creditRequest("CREDIT_NOTE", opvCharge.getId(), "500.00"), opcOrder)).getId());

        for (CustomerAccountEntryEntity credit : new CustomerAccountEntryEntity[] {payment, creditNote}) {
            assertThat(credit.getAppliedToEntryId()).isEqualTo(opvCharge.getId());
            assertThat(credit.getOrderKind()).isEqualTo("OPC");
            assertThat(credit.getProductionOrderId()).isEqualTo(opcOrder.getId());
            assertThat(fx.statementLine(customer, credit.getId()).getOrderKind()).isEqualTo("OPC");
        }

        assertThat(fx.chargeBalance(customer, opvCharge.getId())).isEqualByComparingTo("4212.00");
        assertThat(fx.chargeBalance(customer, opcCharge.getId())).isEqualByComparingTo("2850.00");

        CustomerAccountBalanceResponse balance = fx.accounts.getBalance(customer.getId());
        assertThat(balance.getBalance()).isEqualByComparingTo("7062.00");
        assertThat(balance.getBalanceDueOpv()).isEqualByComparingTo("5712.00");
        assertThat(balance.getBalanceDueOpc()).isEqualByComparingTo("1350.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (correct): a payment linked to the OPV charge with no order falls back to the "
            + "charge's order and kind (OPV)")
    void creditWithoutOrderFallsBackToLinkedCharge() throws Exception {
        CustomerAccountEntryEntity payment = fx.entry(
                fx.create(customer, creditRequest("PAYMENT", opvCharge.getId(), "1000.00")).getId());

        assertThat(payment.getOrderKind()).isEqualTo("OPV");
        assertThat(payment.getProductionOrderId()).isEqualTo(opvOrder.getId());
        assertThat(fx.statementLine(customer, payment.getId()).getOrderKind()).isEqualTo("OPV");

        CustomerAccountBalanceResponse balance = fx.accounts.getBalance(customer.getId());
        assertThat(balance.getBalanceDueOpv()).isEqualByComparingTo("4712.00");
        assertThat(balance.getBalanceDueOpc()).isEqualByComparingTo("2850.00");
    }

    private static CustomerAccountEntryRequest withOrder(CustomerAccountEntryRequest request, ProductionOrderEntity order) {
        request.setProductionOrderId(order.getId());
        return request;
    }
}
