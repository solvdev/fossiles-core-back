package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountStatementLineResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.ADJUSTMENT_NEEDS_SHIPMENT;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.VOID_BLOCKED;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.adjustmentRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance gaps that the flipped characterization cases do not already pin:
 * planned-partial adjustments, void dependents, due dates, and a shipping refund.
 */
class LfReceivablesGapAcceptanceTest extends LfReceivablesH2TestBase {

    @Test
    @DisplayName("A planned partial with no shipment cannot receive a CHARGE_ADJUSTMENT")
    void plannedPartialWithoutShipmentCannotBeAdjusted() throws Exception {
        CustomerEntity customer = fx.customer("GAP-ADJ");
        ProductionOrderEntity order = fx.order(customer, "OPV-T0800", TYPE_OPV, "80.00");
        fx.release(order, 1);
        fx.charge(customer, order, null, null, "80.00");

        assertThatThrownBy(() -> fx.create(customer, adjustmentRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ADJUSTMENT_NEEDS_SHIPMENT);
        assertThat(fx.balance(customer)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Voiding a charge is blocked while a credit note, a return, or an adjustment is active")
    void voidBlockedByCreditNoteReturnOrAdjustment() throws Exception {
        assertVoidBlocked("CREDIT_NOTE", false);
        assertVoidBlocked("RETURN", false);
        assertVoidBlocked("CHARGE_ADJUSTMENT", true);
    }

    @Test
    @DisplayName("No shipment yet: the charge is current. Adjustment due is that shipment sent_at plus creditDays")
    void dueDatesFollowShipmentAndCreditDays() throws Exception {
        CustomerEntity customer = fx.customer("GAP-DUE");
        fx.setCreditDays(customer, 15);
        ProductionOrderEntity unshipped = fx.order(customer, "OPV-T0801", TYPE_OPV, "40.00");
        CustomerAccountEntryResponse open = fx.charge(customer, unshipped, null, null, "40.00");
        CustomerAccountStatementLineResponse openLine = fx.statementLine(customer, open.getId());
        assertThat(openLine.getDueDate()).isNull();
        assertThat(openLine.getLineOpenBalance()).isEqualByComparingTo("40.00");

        ProductionOrderEntity shipped = fx.order(customer, "OPV-T0802", TYPE_OPV, "100.00");
        var early = fx.withSentAt(fx.withShipping(
                fx.shipment(shipped, fx.release(shipped, 1), "ENV-0802-1", "40.00"), "20.00"),
                LocalDate.of(2026, 2, 1));
        var late = fx.withSentAt(fx.withShipping(
                fx.shipment(shipped, fx.release(shipped, 2), "ENV-0802-2", "60.00"), "10.00"),
                LocalDate.of(2026, 4, 1));
        CustomerAccountEntryResponse charge = fx.charge(customer, shipped, null, null, "100.00");
        fx.create(customer, adjustmentRequest(early.getId()));
        fx.create(customer, adjustmentRequest(late.getId()));

        assertThat(fx.statementLine(customer, charge.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(lineForShipment(customer, early.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(lineForShipment(customer, late.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 4, 16));
    }

    @Test
    @DisplayName("PENDING_EDUARDO: changing creditDays recomputes due dates of charges that already exist")
    void changingCreditDaysRecomputesExistingChargeDueDates_PENDING_EDUARDO() throws Exception {
        CustomerEntity customer = fx.customer("GAP-DAYS");
        fx.setCreditDays(customer, 0);
        ProductionOrderEntity order = fx.order(customer, "OPV-T0803", TYPE_OPV, "70.00");
        var shipment = fx.withSentAt(
                fx.shipment(order, fx.release(order, 1), "ENV-0803", "70.00"),
                LocalDate.of(2026, 5, 10));
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "70.00");

        assertThat(fx.statementLine(customer, charge.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 5, 10));
        fx.setCreditDays(customer, 30);
        assertThat(fx.statementLine(customer, charge.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(shipment.getId()).isNotNull();
    }

    @Test
    @DisplayName("A line with no due date is allocated after dated lines of the same charge")
    void nullDueDateIsAllocatedLast() throws Exception {
        CustomerEntity customer = fx.customer("GAP-ALLOC");
        fx.setCreditDays(customer, 0);
        ProductionOrderEntity order = fx.order(customer, "OPV-T0804", TYPE_OPV, "100.00");
        var dated = fx.withSentAt(fx.withShipping(
                fx.shipment(order, fx.release(order, 1), "ENV-0804-1", "40.00"), "40.00"),
                LocalDate.of(2026, 1, 15));
        var undated = fx.withShipping(
                fx.shipment(order, fx.release(order, 2), "ENV-0804-2", "40.00"), "40.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "100.00");
        fx.create(customer, adjustmentRequest(dated.getId()));
        fx.create(customer, adjustmentRequest(undated.getId()));
        CustomerAccountEntryResponse payment = fx.create(customer, creditRequest("PAYMENT", charge.getId(), "120.00"));

        assertThat(fx.statementLine(customer, charge.getId()).getDueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(fx.statementLine(customer, charge.getId()).getAllocatedCredit()).isEqualByComparingTo("100.00");
        assertThat(lineForShipment(customer, dated.getId()).getAllocatedCredit()).isEqualByComparingTo("20.00");
        assertThat(lineForShipment(customer, dated.getId()).getLineOpenBalance()).isEqualByComparingTo("20.00");
        assertThat(lineForShipment(customer, undated.getId()).getDueDate()).isNull();
        assertThat(lineForShipment(customer, undated.getId()).getAllocatedCredit()).isEqualByComparingTo("0.00");
        assertThat(lineForShipment(customer, undated.getId()).getLineOpenBalance()).isEqualByComparingTo("40.00");

        CustomerAccountEntryVoidRequest voidRequest = new CustomerAccountEntryVoidRequest();
        voidRequest.setVoidReason("anula el pago");
        fx.accounts.voidEntry(payment.getId(), voidRequest);
        assertThat(fx.statementLine(customer, charge.getId()).getAllocatedCredit()).isEqualByComparingTo("0.00");
        assertThat(lineForShipment(customer, dated.getId()).getAllocatedCredit()).isEqualByComparingTo("0.00");
        assertThat(lineForShipment(customer, undated.getId()).getLineOpenBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("A shipping refund is a positive linked credit note; a negative amount is rejected")
    void shippingRefundIsAPositiveCreditNote() throws Exception {
        CustomerEntity customer = fx.customer("GAP-REFUND");
        ProductionOrderEntity order = fx.order(customer, "OPV-T0805", TYPE_OPV, "100.00");
        var shipment = fx.withShipping(
                fx.shipment(order, fx.release(order, 1), "ENV-0805", "100.00"), "25.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "100.00");
        fx.create(customer, adjustmentRequest(shipment.getId()));
        assertThat(fx.balance(customer)).isEqualByComparingTo("125.00");

        var negativeShipping = fx.withShipping(
                fx.shipment(order, fx.release(order, 2), "ENV-0805-2", "0.00"), "0.00");
        assertThatThrownBy(() -> fx.create(customer, adjustmentRequest(negativeShipping.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("costo de envío real");
        CustomerAccountEntryRequest negative = creditRequest("CREDIT_NOTE", charge.getId(), "-25.00");
        assertThatThrownBy(() -> fx.create(customer, negative))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("monto válido");

        CustomerAccountEntryRequest refund = creditRequest("CREDIT_NOTE", charge.getId(), "25.00");
        CustomerAccountEntryEntity stored = fx.entry(fx.create(customer, refund).getId());
        assertThat(stored.getEntryType()).isEqualTo("CREDIT_NOTE");
        assertThat(stored.getAmount()).isEqualByComparingTo("25.00");
        assertThat(stored.getAmount()).isPositive();
        assertThat(stored.getAppliedToEntryId()).isEqualTo(charge.getId());
        assertThat(fx.balance(customer)).isEqualByComparingTo("100.00");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("100.00");
    }

    private void assertVoidBlocked(String dependentType, boolean adjustment) throws Exception {
        CustomerEntity customer = fx.customer("GAP-VOID-" + dependentType);
        ProductionOrderEntity order = fx.order(customer, "OPV-" + dependentType, TYPE_OPV, "90.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "90.00");
        if (adjustment) {
            var shipment = fx.withShipping(
                    fx.shipment(order, fx.release(order, 1), "ENV-" + dependentType, "90.00"), "12.00");
            fx.create(customer, adjustmentRequest(shipment.getId()));
        } else {
            fx.create(customer, creditRequest(dependentType, charge.getId(), "10.00"));
        }
        CustomerAccountEntryVoidRequest voidRequest = new CustomerAccountEntryVoidRequest();
        voidRequest.setVoidReason("prueba");
        assertThatThrownBy(() -> fx.accounts.voidEntry(charge.getId(), voidRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage(VOID_BLOCKED)
                .hasMessageNotContaining("Anúlelo")
                .hasMessageNotContaining("registrarlo de nuevo");
        assertThat(fx.entry(charge.getId()).getStatus()).isEqualTo("ACTIVE");
    }

    private CustomerAccountStatementLineResponse lineForShipment(CustomerEntity customer, Long shipmentId) throws Exception {
        return fx.accounts.getStatement(customer.getId(), null, null).getLines().stream()
                .filter(line -> shipmentId.equals(line.getProductShipmentId()))
                .findFirst()
                .orElseThrow();
    }
}
