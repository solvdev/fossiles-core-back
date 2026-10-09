package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountDocumentSettlementRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement / summary reconciliation. Applied credit of PAYMENT, CREDIT_NOTE and RETURN
 * ({@code resolveAppliedCreditAmount}): gross_collected_amount when > 0, else amount + payment_discount_amount.
 * The early-payment discount is inside a payment's gross, so the expected identity is
 * closing = opening + charges - payments(applied) - credit notes(applied) - returns(applied).
 */
class StatementReconciliationCharacterizationTest extends LfReceivablesH2TestBase {

    private static final LocalDate OPENING_DATE = LocalDate.of(2026, 8, 1);

    private CustomerEntity customer;
    private CustomerAccountEntryResponse opvCharge;
    private CustomerAccountEntryResponse opcCharge;
    private CustomerAccountEntryResponse payment;

    /**
     * Opening Q500 (Aug) + OPV charge Q1,000 + OPC charge Q2,000; payment on OPV gross Q600 (net Q570, discount
     * Q30); credit note Q200 and return Q300 on OPC; unlinked credit note Q100. Expected balance Q2,300.
     */
    private void buildMixedAccount() throws Exception {
        customer = fx.customer("CR001-T");
        ProductionOrderEntity opvOrder = fx.order(customer, "OPV-T0400", TYPE_OPV, "1000.00");
        ProductionOrderEntity opcOrder = fx.order(customer, "OPC-T0401", TYPE_OPC, "2000.00");

        CustomerAccountEntryRequest opening = chargeRequest(null, null, null, "500.00");
        opening.setEntryType("OPENING_BALANCE");
        opening.setEntryDate(OPENING_DATE);
        fx.create(customer, opening);

        opvCharge = fx.charge(customer, opvOrder, null, null, "1000.00");
        opcCharge = fx.charge(customer, opcOrder, null, null, "2000.00");

        CustomerAccountEntryRequest discountedPayment = creditRequest("PAYMENT", opvCharge.getId(), "600.00");
        discountedPayment.setPaymentDiscountAmount(new BigDecimal("30.00"));
        payment = fx.create(customer, discountedPayment);

        fx.create(customer, creditRequest("CREDIT_NOTE", opcCharge.getId(), "200.00"));
        fx.create(customer, creditRequest("RETURN", opcCharge.getId(), "300.00"));
        fx.create(customer, creditRequest("CREDIT_NOTE", null, "100.00"));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: statement and balance satisfy opening + charges - payments(gross) - credit notes "
            + "- returns; credit notes are their own total, payment discounts have no total of their own")
    void statementTotalsReconcile() throws Exception {
        buildMixedAccount();

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), ENTRY_DATE, null);
        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("500.00");
        assertThat(statement.getTotalCharges()).isEqualByComparingTo("3000.00");
        assertThat(statement.getTotalPayments()).isEqualByComparingTo("600.00");
        assertThat(statement.getTotalCreditNotes()).isEqualByComparingTo("300.00");
        assertThat(statement.getTotalReturns()).isEqualByComparingTo("300.00");
        assertThat(statement.getClosingBalance())
                .isEqualByComparingTo(statement.getOpeningBalance()
                        .add(statement.getTotalCharges())
                        .subtract(statement.getTotalPayments())
                        .subtract(statement.getTotalCreditNotes())
                        .subtract(statement.getTotalReturns()))
                .isEqualByComparingTo("2300.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("2300.00");

        CustomerAccountStatementLineResponse paymentLine = fx.statementLine(customer, payment.getId());
        assertThat(paymentLine.getCredit()).isEqualByComparingTo("600.00");
        assertThat(paymentLine.getGrossCollectedAmount()).isEqualByComparingTo("600.00");
        assertThat(paymentLine.getPaymentDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(fx.entry(payment.getId()).getAmount()).isEqualByComparingTo("570.00");

        assertThat(Arrays.stream(CustomerAccountStatementResponse.class.getDeclaredFields()).map(Field::getName))
                .contains("totalCreditNotes")
                .noneMatch(name -> name.toLowerCase().contains("discount"));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: without a from-date, an OPENING_BALANCE entry is reported inside totalCharges "
            + "and openingBalance is 0")
    void openingBalanceEntryIsFoldedIntoCharges() throws Exception {
        buildMixedAccount();

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);

        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("0");
        assertThat(statement.getTotalCharges()).isEqualByComparingTo("3500.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("2300.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): OPV/OPC split and per-document balances leave out the opening balance and "
            + "the unlinked credit note (OPV 400 + OPC 1,500 = 1,900 vs balance 2,300) — fix should reconcile")
    void kindSplitAndDocumentsDoNotAddUpToBalance() throws Exception {
        buildMixedAccount();

        CustomerAccountSummaryResponse summary = fx.accounts.getSummary("CR001-T", false, false).stream()
                .filter(r -> r.getCustomerId().equals(customer.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.getBalanceDue()).isEqualByComparingTo("2300.00");
        assertThat(summary.getBalanceDueOpv()).isEqualByComparingTo("400.00");
        assertThat(summary.getBalanceDueOpc()).isEqualByComparingTo("1500.00");

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);
        assertThat(statement.getClosingBalanceDueOpv()).isEqualByComparingTo("400.00");
        assertThat(statement.getClosingBalanceDueOpc()).isEqualByComparingTo("1500.00");

        assertThat(fx.accounts.getReceivableDocuments(customer.getId(), null).stream()
                .map(LfReceivableDocumentResponse::getBalanceDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1900.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: legacy payments with gross NULL or 0 apply amount + discount; a credit note with a "
            + "discount and no gross also applies amount + discount (Q100 + Q10 = Q110)")
    void grossNullOrZeroFallsBackToAmountPlusDiscount() throws Exception {
        customer = fx.customer("CR002-T");
        ProductionOrderEntity order = fx.order(customer, "OPV-T0402", TYPE_OPV, "1000.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "1000.00");
        Long nullGross = fx.insert(legacyPayment(charge, "570.00", "30.00", null)).getId();
        Long zeroGross = fx.insert(legacyPayment(charge, "190.00", "10.00", BigDecimal.ZERO)).getId();

        assertThat(fx.statementLine(customer, nullGross).getCredit()).isEqualByComparingTo("600.00");
        assertThat(fx.statementLine(customer, zeroGross).getCredit()).isEqualByComparingTo("200.00");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("200.00");

        CustomerAccountEntryRequest creditNote = creditRequest("CREDIT_NOTE", charge.getId(), "100.00");
        creditNote.setPaymentDiscountAmount(new BigDecimal("10.00"));
        CustomerAccountEntryResponse note = fx.create(customer, creditNote);
        assertThat(fx.entry(note.getId()).getGrossCollectedAmount()).isNull();

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);
        assertThat(statement.getTotalPayments()).isEqualByComparingTo("800.00");
        assertThat(statement.getTotalCreditNotes()).isEqualByComparingTo("110.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("90.00");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: document settlement books the commercial discount as its own linked CREDIT_NOTE "
            + "and the payment at gross; the early-payment discount is not counted twice (closing 0, not -45)")
    void settledDocumentIsNotDoubleCounted() throws Exception {
        customer = fx.customer("CR003-T");
        ProductionOrderEntity order = fx.order(customer, "OPV-T0403", TYPE_OPV, "1000.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "1000.00");

        CustomerAccountDocumentSettlementRequest settlement = new CustomerAccountDocumentSettlementRequest();
        settlement.setAppliedToEntryId(charge.getId());
        settlement.setDiscountAmount(new BigDecimal("100.00"));
        settlement.setPaymentGross(new BigDecimal("900.00"));
        settlement.setPaymentDiscountAmount(new BigDecimal("45.00"));
        settlement.setReceiptNumber("REC-SETTLE");
        settlement.setEntryDate(ENTRY_DATE);
        settlement.setCollectionDate(ENTRY_DATE);
        CustomerAccountDocumentSettlementResponse result = fx.accounts.createDocumentSettlement(customer.getId(), settlement);

        assertThat(result.getFinalBalance()).isEqualByComparingTo("0");
        assertThat(result.getEntries()).extracting(CustomerAccountEntryResponse::getEntryType)
                .containsExactly("CREDIT_NOTE", "PAYMENT");
        CustomerAccountEntryEntity creditNote = fx.entry(result.getEntries().get(0).getId());
        assertThat(creditNote.getAppliedToEntryId()).isEqualTo(charge.getId());
        assertThat(creditNote.getAmount()).isEqualByComparingTo("100.00");
        CustomerAccountEntryEntity paymentEntry = fx.entry(result.getEntries().get(1).getId());
        assertThat(paymentEntry.getAmount()).isEqualByComparingTo("855.00");
        assertThat(paymentEntry.getGrossCollectedAmount()).isEqualByComparingTo("900.00");
        assertThat(paymentEntry.getPaymentDiscountAmount()).isEqualByComparingTo("45.00");

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);
        assertThat(statement.getTotalCreditNotes()).isEqualByComparingTo("100.00");
        assertThat(statement.getTotalPayments()).isEqualByComparingTo("900.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("0");
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
    }

    private CustomerAccountEntryEntity legacyPayment(
            CustomerAccountEntryResponse charge, String amount, String discount, BigDecimal gross) {
        return CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("PAYMENT")
                .status("ACTIVE")
                .entryDate(ENTRY_DATE)
                .amount(new BigDecimal(amount))
                .paymentDiscountAmount(new BigDecimal(discount))
                .grossCollectedAmount(gross)
                .appliedToEntryId(charge.getId())
                .productionOrderId(charge.getProductionOrderId())
                .build();
    }
}
