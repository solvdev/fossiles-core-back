package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_ORDER_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.PARTIAL_HAS_NO_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.adjustmentRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A charge that payments have brought to zero is still the order's one active charge.
 */
@AutoConfigureMockMvc(addFilters = false)
class SettledOrderChargeAcceptanceTest extends LfReceivablesH2TestBase {

    private static final String DUPLICATE_ADJUSTMENT = "Ya existe un ajuste de envío activo para este parcial.";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("17a. settledOrder_cannotGetSecondCharge")
    void settledOrder_cannotGetSecondCharge() throws Exception {
        Settled settled = settledOrder();
        ProductionOrderPartialReleaseEntity otherRelease = fx.release(settled.order(), 2);
        ProductShipmentEntity otherShipment = fx.shipment(settled.order(), otherRelease, "ENV-17A-2", "100.00");

        rejectCharge(settled, null, null, DUPLICATE_ORDER_CHARGE);
        rejectCharge(settled, otherRelease.getId(), otherShipment.getId(), PARTIAL_HAS_NO_CHARGE);
        rejectCharge(settled, null, otherShipment.getId(), PARTIAL_HAS_NO_CHARGE);
        rejectCharge(settled, otherRelease.getId(), null, PARTIAL_HAS_NO_CHARGE);

        assertThat(fx.balance(settled.customer())).isEqualByComparingTo("0");
        assertThat(activeCharges(settled)).containsExactly(settled.charge().getId());
    }

    @Test
    @DisplayName("17b. settledOrder_cannotGetSecondAdjustment")
    void settledOrder_cannotGetSecondAdjustment() throws Exception {
        Settled settled = settledOrder();
        fx.withShipping(settled.shipment(), "8.00");
        CustomerAccountEntryResponse first = fx.create(settled.customer(), adjustmentRequest(settled.shipment().getId()));

        mvc.perform(post("/api/customer-accounts/customers/{id}/entries", settled.customer().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentJson(settled.shipment().getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(DUPLICATE_ADJUSTMENT));

        assertThat(adjustments(settled)).containsExactly(first.getId());
    }

    @Test
    @DisplayName("17c. settledOrder_voidMovesPaymentsOntoReplacementCharge")
    void settledOrder_voidMovesPaymentsOntoReplacementCharge() throws Exception {
        Settled settled = settledOrder();
        CustomerAccountEntryEntity replacement = fx.insert(LfReceivablesFixture.legacyCharge(
                settled.customer(), settled.order(), null, null, "100.00").build());

        mvc.perform(put("/api/customer-accounts/entries/{id}/void", settled.charge().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voidReason\":\"reemplazo\",\"reassignToChargeId\":" + replacement.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        CustomerAccountEntryEntity payment = fx.entry(settled.payment().getId());
        assertThat(payment.getAppliedToEntryId()).isEqualTo(replacement.getId());
        assertThat(payment.getProductionOrderId()).isEqualTo(settled.order().getId());
        assertThat(fx.entry(settled.charge().getId()).getStatus()).isEqualTo("VOID");
        assertThat(activeCharges(settled)).containsExactly(replacement.getId());
    }

    private void rejectCharge(Settled settled, Long partialId, Long shipmentId, String message) throws Exception {
        mvc.perform(post("/api/customer-accounts/customers/{id}/entries", settled.customer().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chargeJson(settled.order().getId(), partialId, shipmentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    private Settled settledOrder() throws Exception {
        CustomerEntity customer = fx.customer("SETTLED-17");
        ProductionOrderEntity order = fx.order(customer, "OPV-17", TYPE_OPV, "100.00");
        ProductionOrderPartialReleaseEntity release = fx.release(order, 1);
        ProductShipmentEntity shipment = fx.shipment(order, release, "ENV-17", "100.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "100.00");
        CustomerAccountEntryResponse payment = fx.create(customer, creditRequest("PAYMENT", charge.getId(), "100.00"));
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("0");
        return new Settled(customer, order, shipment, charge, payment);
    }

    private List<Long> activeCharges(Settled settled) {
        return fx.entries(settled.customer()).stream()
                .filter(entry -> "CHARGE".equals(entry.getEntryType()))
                .filter(entry -> "ACTIVE".equals(entry.getStatus()))
                .filter(entry -> settled.order().getId().equals(entry.getProductionOrderId()))
                .map(CustomerAccountEntryEntity::getId)
                .toList();
    }

    private List<Long> adjustments(Settled settled) {
        return fx.entries(settled.customer()).stream()
                .filter(entry -> "CHARGE_ADJUSTMENT".equals(entry.getEntryType()))
                .filter(entry -> "ACTIVE".equals(entry.getStatus()))
                .map(CustomerAccountEntryEntity::getId)
                .toList();
    }

    private static String chargeJson(Long orderId, Long partialId, Long shipmentId) {
        StringBuilder json = new StringBuilder()
                .append("{\"entryType\":\"CHARGE\",\"entryDate\":\"2026-09-01\",\"amount\":100.00,\"productionOrderId\":")
                .append(orderId);
        if (partialId != null) {
            json.append(",\"partialReleaseId\":").append(partialId);
        }
        if (shipmentId != null) {
            json.append(",\"productShipmentId\":").append(shipmentId);
        }
        return json.append('}').toString();
    }

    private static String adjustmentJson(Long shipmentId) {
        return "{\"entryType\":\"CHARGE_ADJUSTMENT\",\"entryDate\":\"2026-09-01\",\"amount\":8.00,\"productShipmentId\":"
                + shipmentId + "}";
    }

    private record Settled(
            CustomerEntity customer,
            ProductionOrderEntity order,
            ProductShipmentEntity shipment,
            CustomerAccountEntryResponse charge,
            CustomerAccountEntryResponse payment) {
    }
}
