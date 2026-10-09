package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountDocumentSettlementRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountDocumentSettlementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountStatementLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountStatementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfReceivableDocumentResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.ENTRY_DATE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.adjustmentRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.openingRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.q;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * closing = opening + charges + adjustments − payments − credit notes − returns,
 * using {@code resolveEntryAppliedCredit}. An opening balance has no order kind.
 */
class StatementReconciliationAcceptanceTest extends LfReceivablesH2TestBase {

    private static final LocalDate OPENING_DATE = LocalDate.of(2026, 8, 1);

    private CustomerEntity customer;
    private CustomerAccountEntryResponse opvCharge;
    private CustomerAccountEntryResponse opcCharge;
    private CustomerAccountEntryResponse payment;

    /**
     * Opening Q500 + OPV charge Q1,000 + OPC charge Q2,000 − payment gross Q600 − credit note Q200 − return Q300
     * = Q2,400. OPV due Q400, OPC due Q1,500, opening Q500.
     */
    private void buildMixedAccount() throws Exception {
        customer = fx.customer("CR001-T");
        ProductionOrderEntity opvOrder = fx.order(customer, "OPV-T0400", TYPE_OPV, "1000.00");
        ProductionOrderEntity opcOrder = fx.order(customer, "OPC-T0401", TYPE_OPC, "2000.00");

        fx.create(customer, openingRequest("500.00", OPENING_DATE));
        opvCharge = fx.charge(customer, opvOrder, null, null, "1000.00");
        opcCharge = fx.charge(customer, opcOrder, null, null, "2000.00");

        CustomerAccountEntryRequest discountedPayment = creditRequest("PAYMENT", opvCharge.getId(), "600.00");
        discountedPayment.setPaymentDiscountAmount(new BigDecimal("30.00"));
        payment = fx.create(customer, discountedPayment);
        fx.create(customer, creditRequest("CREDIT_NOTE", opcCharge.getId(), "200.00"));
        fx.create(customer, creditRequest("RETURN", opcCharge.getId(), "300.00"));
    }

    @Test
    @DisplayName("Statement totals satisfy opening + charges − applied credits")
    void statementTotalsReconcile() throws Exception {
        buildMixedAccount();

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), ENTRY_DATE, null);
        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("500.00");
        assertThat(statement.getTotalCharges()).isEqualByComparingTo("3000.00");
        assertThat(statement.getTotalPayments()).isEqualByComparingTo("600.00");
        assertThat(statement.getTotalCreditNotes()).isEqualByComparingTo("200.00");
        assertThat(statement.getTotalReturns()).isEqualByComparingTo("300.00");
        assertThat(statement.getClosingBalance())
                .isEqualByComparingTo(statement.getOpeningBalance()
                        .add(statement.getTotalCharges())
                        .subtract(statement.getTotalPayments())
                        .subtract(statement.getTotalCreditNotes())
                        .subtract(statement.getTotalReturns()))
                .isEqualByComparingTo("2400.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("2400.00");

        CustomerAccountStatementLineResponse paymentLine = fx.statementLine(customer, payment.getId());
        assertThat(paymentLine.getCredit()).isEqualByComparingTo("600.00");
        assertThat(paymentLine.getGrossCollectedAmount()).isEqualByComparingTo("600.00");
        assertThat(paymentLine.getPaymentDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(fx.entry(payment.getId()).getAmount()).isEqualByComparingTo("570.00");
        assertThat(fx.accounts.resolveEntryAppliedCredit(fx.entry(payment.getId()))).isEqualByComparingTo("600.00");
        assertThat(Arrays.stream(CustomerAccountStatementResponse.class.getDeclaredFields()).map(Field::getName))
                .contains("totalCreditNotes")
                .noneMatch(name -> name.toLowerCase().contains("discount"));
    }

    @Test
    @DisplayName("Without a from-date, OPENING_BALANCE is inside totalCharges and openingBalance is 0")
    void openingBalanceEntryIsFoldedIntoCharges() throws Exception {
        buildMixedAccount();

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);
        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("0");
        assertThat(statement.getTotalCharges()).isEqualByComparingTo("3500.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("2400.00");
    }

    @Test
    @DisplayName("OPV due + OPC due + opening balance equals the customer balance")
    void kindSplitAndDocumentsDoNotAddUpToBalance() throws Exception {
        buildMixedAccount();

        CustomerAccountSummaryResponse summary = fx.accounts.getSummary("CR001-T", false, false).stream()
                .filter(row -> row.getCustomerId().equals(customer.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.getBalanceDue()).isEqualByComparingTo("2400.00");
        assertThat(summary.getBalanceDueOpv()).isEqualByComparingTo("400.00");
        assertThat(summary.getBalanceDueOpc()).isEqualByComparingTo("1500.00");
        assertThat(summary.getBalanceDueOpv().add(summary.getBalanceDueOpc()).add(q("500.00")))
                .isEqualByComparingTo(summary.getBalanceDue());

        CustomerAccountStatementResponse statement = fx.accounts.getStatement(customer.getId(), null, null);
        assertThat(statement.getClosingBalanceDueOpv()).isEqualByComparingTo("400.00");
        assertThat(statement.getClosingBalanceDueOpc()).isEqualByComparingTo("1500.00");
        assertThat(statement.getClosingBalanceDueOpv()
                .add(statement.getClosingBalanceDueOpc())
                .add(q("500.00")))
                .isEqualByComparingTo(statement.getClosingBalance());

        assertThat(fx.accounts.getReceivableDocuments(customer.getId(), null).stream()
                .map(LfReceivableDocumentResponse::getBalanceDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1900.00");
    }

    @Test
    @DisplayName("Adjustments are debits in the customer balance and in the kind split")
    void adjustmentsAndKindSplitReconcileWithAppliedCredit() throws Exception {
        customer = fx.customer("CR004-T");
        ProductionOrderEntity opvOrder = fx.order(customer, "OPV-T0404", TYPE_OPV, "200.00");
        ProductionOrderEntity opcOrder = fx.order(customer, "OPC-T0405", TYPE_OPC, "300.00");
        var shipment = fx.withShipping(
                fx.shipment(opvOrder, fx.release(opvOrder, 1), "ENVP-90404-ENV-00001", "200.00"), "25.00");
        fx.create(customer, openingRequest("100.00", OPENING_DATE));
        CustomerAccountEntryResponse opv = fx.charge(customer, opvOrder, null, null, "200.00");
        CustomerAccountEntryResponse opc = fx.charge(customer, opcOrder, null, null, "300.00");
        CustomerAccountEntryResponse adjustment = fx.create(customer, adjustmentRequest(shipment.getId()));
        assertThat(fx.entry(adjustment.getId()).getAmount()).isEqualByComparingTo("25.00");
        assertThat(fx.entry(adjustment.getId()).getOrderKind()).isEqualTo("OPV");

        CustomerAccountEntryRequest discounted = creditRequest("PAYMENT", opv.getId(), "50.00");
        discounted.setPaymentDiscountAmount(q("10.00"));
        CustomerAccountEntryResponse pay = fx.create(customer, discounted);
        CustomerAccountEntryResponse note = fx.create(customer, creditRequest("CREDIT_NOTE", opc.getId(), "30.00"));
        CustomerAccountEntryResponse returned = fx.create(customer, creditRequest("RETURN", opc.getId(), "20.00"));

        BigDecimal applied = fx.accounts.resolveEntryAppliedCredit(fx.entry(pay.getId()))
                .add(fx.accounts.resolveEntryAppliedCredit(fx.entry(note.getId())))
                .add(fx.accounts.resolveEntryAppliedCredit(fx.entry(returned.getId())));
        assertThat(applied).isEqualByComparingTo("100.00");

        BigDecimal balance = q("100.00").add(q("200.00")).add(q("25.00")).add(q("300.00")).subtract(applied);
        assertThat(fx.balance(customer)).isEqualByComparingTo(balance).isEqualByComparingTo("525.00");
        var summary = fx.accounts.getBalance(customer.getId());
        assertThat(summary.getBalanceDueOpv()).isEqualByComparingTo("175.00");
        assertThat(summary.getBalanceDueOpc()).isEqualByComparingTo("250.00");
        assertThat(summary.getBalanceDueOpv().add(summary.getBalanceDueOpc()).add(q("100.00")))
                .isEqualByComparingTo("525.00");
    }

    @Test
    @DisplayName("Legacy payments with gross null or zero apply amount plus discount")
    void grossNullOrZeroFallsBackToAmountPlusDiscount() throws Exception {
        customer = fx.customer("CR002-T");
        ProductionOrderEntity order = fx.order(customer, "OPV-T0402", TYPE_OPV, "1000.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "1000.00");
        Long nullGross = fx.insert(legacyPayment(charge, "570.00", "30.00", null)).getId();
        Long zeroGross = fx.insert(legacyPayment(charge, "190.00", "10.00", BigDecimal.ZERO)).getId();

        assertThat(fx.statementLine(customer, nullGross).getCredit()).isEqualByComparingTo("600.00");
        assertThat(fx.statementLine(customer, zeroGross).getCredit()).isEqualByComparingTo("200.00");
        assertThat(fx.accounts.resolveEntryAppliedCredit(fx.entry(nullGross))).isEqualByComparingTo("600.00");
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
    @DisplayName("Document settlement books the commercial discount once")
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
