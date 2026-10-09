package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cap of credits against a charge's open balance (createEntry, applied credit compared with balanceDue). */
class PaymentCapCharacterizationTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private CustomerAccountEntryResponse charge;

    @BeforeEach
    void setUpCharge() throws Exception {
        customer = fx.customer("CP001-T");
        charge = fx.charge(customer, fx.order(customer, "OPV-T0500", TYPE_OPV, "1000.00"), null, null, "1000.00");
    }

    @ParameterizedTest(name = "CURRENT BEHAVIOR (matches target): linked {0} above the open balance is rejected, not capped")
    @ValueSource(strings = {"PAYMENT", "CREDIT_NOTE", "RETURN"})
    void linkedCreditAboveBalanceIsRejected(String entryType) throws Exception {
        assertThatThrownBy(() -> fx.create(customer, creditRequest(entryType, charge.getId(), "1000.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto excede el saldo pendiente del documento (Q 1000.00).");

        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("1000.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: the cap compares the payment gross, so net Q990 + discount Q60 on a Q1,000 "
            + "balance is rejected")
    void capUsesGrossIncludingDiscount() {
        CustomerAccountEntryRequest payment = creditRequest("PAYMENT", charge.getId(), "1050.00");
        payment.setPaymentDiscountAmount(new BigDecimal("60.00"));

        assertThatThrownBy(() -> fx.create(customer, payment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("excede el saldo pendiente");
    }

    @ParameterizedTest(name = "CURRENT BEHAVIOR (bug): unlinked {0} has no cap; the customer goes to a credit balance "
            + "while the charge stays fully open — fix should flip to: require a link to the order charge, capped at its balance")
    @ValueSource(strings = {"PAYMENT", "CREDIT_NOTE", "RETURN"})
    void unlinkedCreditHasNoCap(String entryType) throws Exception {
        fx.create(customer, creditRequest(entryType, null, "1500.00"));

        assertThat(fx.balance(customer)).isEqualByComparingTo("-500.00");
        assertThat(fx.accounts.getBalance(customer.getId()).getCreditBalance()).isEqualByComparingTo("500.00");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (matches target): two sequential linked payments that together exceed the "
            + "balance -> the second is rejected against the remaining Q400")
    void secondLinkedPaymentOverRemainingBalanceIsRejected() throws Exception {
        fx.create(customer, creditRequest("PAYMENT", charge.getId(), "600.00"));

        assertThatThrownBy(() -> fx.create(customer, creditRequest("PAYMENT", charge.getId(), "600.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto excede el saldo pendiente del documento (Q 400.00).");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("400.00");
    }
}
