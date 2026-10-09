package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.adjustmentRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voiding a charge that still has payments, credit notes, or returns goes through
 * {@code PUT /api/customer-accounts/entries/{entryId}/void}. {@code reassignToChargeId} is optional.
 * BusinessException from that call is HTTP 400.
 */
@AutoConfigureMockMvc(addFilters = false)
class ChargeReassignmentAcceptanceTest extends LfReceivablesH2TestBase {

    private static final String REASON = "cargo equivocado";
    private static final String NEEDS_TARGET =
            "Este cargo tiene pagos, notas de crédito o devoluciones activos. Indique el cargo al que deben trasladarse.";
    private static final String VOID_ADJUSTMENTS_FIRST =
            "Este cargo tiene ajustes de envío activos. Anúlelos primero; solo pueden pasar a un cargo de la misma orden.";

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT", "CREDIT_NOTE", "RETURN"})
    @DisplayName("5a. Active credits and no reassignToChargeId: 400, nothing changes")
    void activeCreditsWithoutTargetAreRejected(String creditType) throws Exception {
        CustomerEntity customer = fx.customer("VOID-5A-" + creditType);
        ProductionOrderEntity order = fx.order(customer, "OPV-5A-" + creditType, TYPE_OPV, "90.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "90.00");
        fx.create(customer, creditRequest(creditType, charge.getId(), "10.00"));
        List<String> before = ledger(customer);

        mvc.perform(put("/api/customer-accounts/entries/{id}/void", charge.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NEEDS_TARGET));

        assertThat(ledger(customer)).isEqualTo(before);
    }

    @Test
    @DisplayName("5b. Credits move to another order and kind; the target open balance drops by that total")
    void creditsMoveToAnotherOrderAndKind() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5B");
        ProductionOrderEntity sourceOrder = fx.order(customer, "OPV-5B", TYPE_OPV, "200.00");
        ProductionOrderEntity targetOrder = fx.order(customer, "OPC-5B", TYPE_OPC, "500.00");
        CustomerAccountEntryResponse source = fx.charge(customer, sourceOrder, null, null, "200.00");
        CustomerAccountEntryResponse target = fx.charge(customer, targetOrder, null, null, "500.00");
        CustomerAccountEntryResponse payment = fx.create(customer, creditRequest("PAYMENT", source.getId(), "40.00"));
        CustomerAccountEntryResponse note = fx.create(customer, creditRequest("CREDIT_NOTE", source.getId(), "15.00"));
        CustomerAccountEntryResponse returned = fx.create(customer, creditRequest("RETURN", source.getId(), "10.00"));
        BigDecimal moved = new BigDecimal("65.00");
        BigDecimal voidedCharge = new BigDecimal("200.00");
        BigDecimal balanceBefore = fx.balance(customer);
        BigDecimal creditsBefore = activeCreditTotal(customer);
        BigDecimal targetOpenBefore = fx.chargeBalance(customer, target.getId());

        mvc.perform(put("/api/customer-accounts/entries/{id}/void", source.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        assertThat(fx.entry(source.getId()).getStatus()).isEqualTo("VOID");
        for (CustomerAccountEntryResponse credit : List.of(payment, note, returned)) {
            CustomerAccountEntryEntity movedRow = fx.entry(credit.getId());
            assertThat(movedRow.getStatus()).isEqualTo("ACTIVE");
            assertThat(movedRow.getAppliedToEntryId()).isEqualTo(target.getId());
            assertThat(movedRow.getProductionOrderId()).isEqualTo(targetOrder.getId());
            assertThat(movedRow.getOrderKind()).isEqualTo("OPC");
            assertThat(movedRow.getReassignedFromEntryId()).isEqualTo(source.getId());
            assertThat(movedRow.getDescription()).contains(REASON);
        }
        assertThat(fx.chargeBalance(customer, target.getId())).isEqualByComparingTo(targetOpenBefore.subtract(moved));
        assertThat(activeCreditTotal(customer))
                .as("active credit total")
                .isEqualByComparingTo(creditsBefore)
                .isEqualByComparingTo(moved);
        assertThat(fx.balance(customer))
                .as("customer total balance")
                .isEqualByComparingTo(balanceBefore.subtract(voidedCharge));
    }

    @Test
    @DisplayName("5c. A void, non-charge, foreign, or self target: 400, nothing changes")
    void badTargetsChangeNothing() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5C");
        CustomerEntity other = fx.customer("VOID-5C-OTHER");
        ProductionOrderEntity sourceOrder = fx.order(customer, "OPV-5C", TYPE_OPV, "100.00");
        ProductionOrderEntity voidOrder = fx.order(customer, "OPV-5C-V", TYPE_OPV, "40.00");
        ProductionOrderEntity foreignOrder = fx.order(other, "OPV-5C-F", TYPE_OPV, "100.00");
        CustomerAccountEntryResponse source = fx.charge(customer, sourceOrder, null, null, "100.00");
        CustomerAccountEntryResponse payment = fx.create(customer, creditRequest("PAYMENT", source.getId(), "20.00"));
        CustomerAccountEntryResponse voided = fx.charge(customer, voidOrder, null, null, "40.00");
        CustomerAccountEntryResponse foreign = fx.charge(other, foreignOrder, null, null, "100.00");
        CustomerAccountEntryResponse opening = fx.create(customer, LfReceivablesFixture.openingRequest("5.00", LfReceivablesFixture.ENTRY_DATE));

        voidOk(voided.getId(), null);
        List<String> before = ledger(customer);

        reject(source.getId(), voided.getId(), "El cargo destino debe ser un cargo activo.");
        reject(source.getId(), opening.getId(), "El cargo destino debe ser un cargo activo.");
        reject(source.getId(), foreign.getId(), "El cargo destino no pertenece a este cliente.");
        reject(source.getId(), source.getId(), "El cargo destino no puede ser el mismo movimiento.");

        assertThat(ledger(customer)).isEqualTo(before);
        assertThat(fx.entry(payment.getId()).getAppliedToEntryId()).isEqualTo(source.getId());
        assertThat(fx.entry(foreign.getId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("5d. Moved total above the target open balance: 400, every row stays")
    void movedTotalAboveTargetOpenBalanceChangesNothing() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5D");
        ProductionOrderEntity sourceOrder = fx.order(customer, "OPV-5D", TYPE_OPV, "100.00");
        ProductionOrderEntity targetOrder = fx.order(customer, "OPV-5D-T", TYPE_OPV, "50.00");
        CustomerAccountEntryResponse source = fx.charge(customer, sourceOrder, null, null, "100.00");
        CustomerAccountEntryResponse target = fx.charge(customer, targetOrder, null, null, "50.00");
        fx.create(customer, creditRequest("PAYMENT", source.getId(), "80.00"));
        List<String> before = ledger(customer);

        mvc.perform(put("/api/customer-accounts/entries/{id}/void", source.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "El monto a trasladar (Q 80.00) supera el saldo pendiente del cargo destino (Q 50.00)."));

        assertThat(ledger(customer)).isEqualTo(before);
    }

    @Test
    @DisplayName("5e. Adjustments move on the same order and are refused on another order")
    void adjustmentsMoveOnlyWithinTheSameOrder() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5E");
        ProductionOrderEntity order = fx.order(customer, "OPV-5E", TYPE_OPV, "80.00");
        var shipment = fx.withShipping(
                fx.shipment(order, fx.release(order, 1), "ENV-5E", "80.00"), "12.00");
        CustomerAccountEntryResponse source = fx.charge(customer, order, null, null, "80.00");
        CustomerAccountEntryResponse adjustment = fx.create(customer, adjustmentRequest(shipment.getId()));
        CustomerAccountEntryEntity sameOrderTarget = fx.insert(LfReceivablesFixture.legacyCharge(
                customer, order, null, null, "80.00").orderKind("OPV").build());

        voidOk(source.getId(), sameOrderTarget.getId());

        CustomerAccountEntryEntity moved = fx.entry(adjustment.getId());
        assertThat(fx.entry(source.getId()).getStatus()).isEqualTo("VOID");
        assertThat(moved.getStatus()).isEqualTo("ACTIVE");
        assertThat(moved.getAppliedToEntryId()).isEqualTo(sameOrderTarget.getId());
        assertThat(moved.getProductionOrderId()).isEqualTo(order.getId());
        assertThat(moved.getProductShipmentId()).isEqualTo(shipment.getId());
        assertThat(moved.getOrderKind()).isEqualTo("OPV");
        assertThat(moved.getReassignedFromEntryId()).isEqualTo(source.getId());
        assertThat(moved.getDescription()).contains(REASON);

        ProductionOrderEntity otherOrder = fx.order(customer, "OPC-5E", TYPE_OPC, "90.00");
        var otherShipment = fx.withShipping(
                fx.shipment(otherOrder, fx.release(otherOrder, 1), "ENV-5E-B", "90.00"), "9.00");
        CustomerAccountEntryResponse blocked = fx.charge(customer, otherOrder, null, null, "90.00");
        CustomerAccountEntryResponse blockedAdjustment = fx.create(customer, adjustmentRequest(otherShipment.getId()));
        ProductionOrderEntity elsewhere = fx.order(customer, "OPV-5E-X", TYPE_OPV, "90.00");
        CustomerAccountEntryResponse elsewhereCharge = fx.charge(customer, elsewhere, null, null, "90.00");
        List<String> before = ledger(customer);

        mvc.perform(put("/api/customer-accounts/entries/{id}/void", blocked.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(elsewhereCharge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(VOID_ADJUSTMENTS_FIRST));

        assertThat(ledger(customer)).isEqualTo(before);
        assertThat(fx.entry(blockedAdjustment.getId()).getAppliedToEntryId()).isEqualTo(blocked.getId());
    }

    @Test
    @DisplayName("5f. The first charge id stays on the row and in the API after a second move")
    void reassignmentTraceKeepsTheFirstCharge() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5F");
        ProductionOrderEntity firstOrder = fx.order(customer, "OPV-5F-A", TYPE_OPV, "100.00");
        ProductionOrderEntity secondOrder = fx.order(customer, "OPV-5F-B", TYPE_OPV, "200.00");
        ProductionOrderEntity thirdOrder = fx.order(customer, "OPV-5F-C", TYPE_OPV, "200.00");
        CustomerAccountEntryResponse first = fx.charge(customer, firstOrder, null, null, "100.00");
        CustomerAccountEntryResponse second = fx.charge(customer, secondOrder, null, null, "200.00");
        CustomerAccountEntryResponse third = fx.charge(customer, thirdOrder, null, null, "200.00");
        CustomerAccountEntryResponse payment = fx.create(customer, creditRequest("PAYMENT", first.getId(), "30.00"));

        voidOk(first.getId(), second.getId(), "primero");

        CustomerAccountEntryEntity once = fx.entry(payment.getId());
        assertThat(once.getAppliedToEntryId()).isEqualTo(second.getId());
        assertThat(once.getReassignedFromEntryId()).isEqualTo(first.getId());
        assertThat(once.getDescription()).isEqualTo("primero");
        JsonNode onceLine = statementLine(customer.getId(), payment.getId());
        assertThat(onceLine.get("reassignedFromEntryId").asLong()).isEqualTo(first.getId());
        assertThat(onceLine.get("description").asText()).isEqualTo("primero");
        assertThat(fx.statementLine(customer, payment.getId()).getReassignedFromEntryId()).isEqualTo(first.getId());

        voidOk(second.getId(), third.getId(), "segundo");

        CustomerAccountEntryEntity twice = fx.entry(payment.getId());
        assertThat(twice.getAppliedToEntryId()).isEqualTo(third.getId());
        assertThat(twice.getReassignedFromEntryId()).isEqualTo(first.getId());
        assertThat(twice.getDescription()).isEqualTo("primero\nsegundo");
        JsonNode twiceLine = statementLine(customer.getId(), payment.getId());
        assertThat(twiceLine.get("reassignedFromEntryId").asLong()).isEqualTo(first.getId());
        assertThat(twiceLine.get("description").asText()).isEqualTo("primero\nsegundo");
        assertThat(twiceLine.get("appliedToEntryId").asLong()).isEqualTo(third.getId());
        assertThat(fx.statementLine(customer, payment.getId()).getReassignedFromEntryId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("5h. A charge with no active items voids without reassignToChargeId")
    void chargeWithoutActiveItemsVoidsWithoutTarget() throws Exception {
        CustomerEntity customer = fx.customer("VOID-5H");
        ProductionOrderEntity order = fx.order(customer, "OPV-5H", TYPE_OPV, "30.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "30.00");

        voidOk(charge.getId(), null);

        CustomerAccountEntryEntity voided = fx.entry(charge.getId());
        assertThat(voided.getStatus()).isEqualTo("VOID");
        assertThat(voided.getVoidReason()).isEqualTo(REASON);
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
    }

    private void voidOk(Long entryId, Long reassignToChargeId) throws Exception {
        voidOk(entryId, reassignToChargeId, REASON);
    }

    private void voidOk(Long entryId, Long reassignToChargeId, String reason) throws Exception {
        mvc.perform(put("/api/customer-accounts/entries/{id}/void", entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reassignToChargeId, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
    }

    private JsonNode statementLine(Long customerId, Long entryId) throws Exception {
        String json = mvc.perform(get("/api/customer-accounts/customers/{id}/statement", customerId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode line : new ObjectMapper().readTree(json).get("lines")) {
            if (line.get("id").asLong() == entryId) {
                return line;
            }
        }
        throw new AssertionError("statement has no line " + entryId);
    }

    private void reject(Long entryId, Long reassignToChargeId, String message) throws Exception {
        mvc.perform(put("/api/customer-accounts/entries/{id}/void", entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reassignToChargeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    private static String body(Long reassignToChargeId) {
        return body(reassignToChargeId, REASON);
    }

    private static String body(Long reassignToChargeId, String reason) {
        if (reassignToChargeId == null) {
            return "{\"voidReason\":\"" + reason + "\"}";
        }
        return "{\"voidReason\":\"" + reason + "\",\"reassignToChargeId\":" + reassignToChargeId + "}";
    }

    private BigDecimal activeCreditTotal(CustomerEntity customer) {
        BigDecimal total = BigDecimal.ZERO;
        for (CustomerAccountEntryEntity entry : fx.entries(customer)) {
            if (!"ACTIVE".equalsIgnoreCase(entry.getStatus()) || !isCredit(entry.getEntryType())) {
                continue;
            }
            total = total.add(appliedCredit(entry));
        }
        return total;
    }

    private static boolean isCredit(String entryType) {
        return "PAYMENT".equalsIgnoreCase(entryType)
                || "CREDIT_NOTE".equalsIgnoreCase(entryType)
                || "RETURN".equalsIgnoreCase(entryType);
    }

    private static BigDecimal appliedCredit(CustomerAccountEntryEntity entry) {
        if (entry.getGrossCollectedAmount() != null && entry.getGrossCollectedAmount().compareTo(BigDecimal.ZERO) > 0) {
            return entry.getGrossCollectedAmount();
        }
        BigDecimal amount = entry.getAmount() == null ? BigDecimal.ZERO : entry.getAmount();
        if (entry.getPaymentDiscountAmount() != null && entry.getPaymentDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return amount.add(entry.getPaymentDiscountAmount());
        }
        return amount;
    }

    private List<String> ledger(CustomerEntity customer) {
        return fx.entries(customer).stream().map(ChargeReassignmentAcceptanceTest::fingerprint).toList();
    }

    private static String fingerprint(CustomerAccountEntryEntity row) {
        return String.join("|",
                String.valueOf(row.getId()),
                row.getEntryType(),
                row.getStatus(),
                plain(row.getAmount()),
                String.valueOf(row.getProductionOrderId()),
                String.valueOf(row.getProductShipmentId()),
                String.valueOf(row.getAppliedToEntryId()),
                String.valueOf(row.getOrderKind()),
                String.valueOf(row.getReassignedFromEntryId()),
                String.valueOf(row.getDescription()),
                String.valueOf(row.getVoidReason()),
                plain(row.getGrossCollectedAmount()));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
