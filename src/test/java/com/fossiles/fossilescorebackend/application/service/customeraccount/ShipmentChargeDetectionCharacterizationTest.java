package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfShipmentDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Case 1 (CA502-like): OPC order, shipment ENVP-…-ENV-00001 estimated at Q1,968, charged Q1,783 and fully paid.
 * The OPV/OPC shipments list ({@code OpvShipmentCatalogService}) offers GENERAR CARGO when a row has
 * {@code hasCharge=false}; it decides that with the JPQL {@code CustomerAccountEntryRepository.findActiveCharge},
 * an exact match on (customer, production order, partial release, product shipment) over ACTIVE charges.
 * The receivables search ({@code CustomerAccountService.searchReceivables}) uses an in-memory exact match plus
 * {@code findOverlappingCharge}/{@code isOrderFullyCharged} (COVERED); {@code createEntry} uses
 * {@code findOverlappingCharge} only when the request carries a production order.
 */
class ShipmentChargeDetectionCharacterizationTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release;
    private ProductShipmentEntity shipment;

    @BeforeEach
    void setUpShipment() {
        customer = fx.customer("CA502-T");
        order = fx.order(customer, "OPC-T0028", TYPE_OPC, "1968.00");
        release = fx.release(order, 1);
        shipment = fx.shipment(order, release, "ENVP-90028-ENV-00001", "1968.00");
    }

    private void payInFull(Long chargeId) throws Exception {
        fx.create(customer, creditRequest("PAYMENT", chargeId, "1000.00"));
        fx.create(customer, creditRequest("PAYMENT", chargeId, "783.00"));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: charge on the exact shipment, fully paid -> list reports PAID (no GENERAR CARGO) "
            + "although charge Q1,783 != estimate Q1,968; a second charge is rejected (target: the charge belongs to "
            + "the order, keyed to the parent ENVP number, not to the ENV-0000n shipment)")
    void exactShipmentCharge_settled_isReportedPaidAndBlocksSecondCharge() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, release, shipment, "1783.00");
        payInFull(charge.getId());

        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isTrue();
        assertThat(row.getChargeStatus()).isEqualTo("PAID");
        assertThat(row.getEstimatedTotal()).isEqualByComparingTo("1968.00");

        List<CustomerAccountReceivableSearchResponse> rows = fx.receivableRows(customer, shipment);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getChargeEntryId()).isEqualTo(charge.getId());
            assertThat(r.getChargeStatus()).isEqualTo("PAID");
            assertThat(r.getChargedAmount()).isEqualByComparingTo("1783.00");
            assertThat(r.getEstimatedTotal()).isEqualByComparingTo("1968.00");
        });

        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(duplicateOf("1783.00", "0.00"));
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): settled order-level charge Q1,783 (NULL partial, NULL shipment) against a "
            + "Q1,968 estimate — OpvShipmentCatalogService still reports NONE (findActiveCharge is an exact key), "
            + "while resolveDocumentChargeStatus returns COVERED via findOverlappingCharge even though "
            + "isOrderFullyCharged is false (1783 < 1968); the 2nd charge is rejected "
            + "— fix should flip to: one order charge keyed to the parent ENVP, and the shipment shows covered")
    void orderLevelChargeBelowEstimate_catalogUncharged_secondRejected() throws Exception {
        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1968.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "1783.00");
        payInFull(charge.getId());

        assertThat(fx.entry(charge.getId()).getPartialReleaseId()).isNull();
        assertThat(fx.entry(charge.getId()).getProductShipmentId()).isNull();
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isFalse();
        assertThat(row.getChargeStatus()).isEqualTo("NONE");
        assertThat(row.getEstimatedTotal()).isEqualByComparingTo("1968.00");

        assertThat(fx.receivableRows(customer, shipment)).isEmpty();
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("COVERED");
        assertThat(lfDocument().getChargeStatus()).isEqualTo("PAID");
        assertThat(lfDocument().getChargeEntryId()).isEqualTo(charge.getId());

        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(duplicateOf("1783.00", "0.00"));
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): settled charge on the partial release only (shipment id NULL), amount "
            + "Q1,783 != estimate Q1,968 — catalog still reports NONE, the shipment document is COVERED by "
            + "findOverlappingCharge (same release), and a 2nd charge on the shipment is rejected "
            + "— fix should flip to: the order's single charge covers the shipment in every read model")
    void partialReleaseChargeWithNullShipment_catalogUncharged_secondRejected() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, release, null, "1783.00");
        payInFull(charge.getId());

        assertThat(fx.entry(charge.getId()).getPartialReleaseId()).isEqualTo(release.getId());
        assertThat(fx.entry(charge.getId()).getProductShipmentId()).isNull();

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isFalse();
        assertThat(row.getChargeStatus()).isEqualTo("NONE");

        assertThat(fx.receivableRows(customer, shipment)).isEmpty();
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("COVERED");

        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(duplicateOf("1783.00", "0.00"));
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): a settled Q1,968 charge on a sibling partial with no shipment makes "
            + "isOrderFullyCharged true without findOverlappingCharge, so resolveDocumentChargeStatus is COVERED "
            + "and cartera hides the real shipment, but the catalog stays NONE and a 2nd charge on that shipment "
            + "is accepted — fix should flip to: reject the second charge")
    void siblingPartialFullyCharged_coveredWithoutOverlap_secondChargeAccepted() throws Exception {
        assertThat(fx.accounts.estimateVendorOrderTotal(order)).isEqualByComparingTo("1968.00");
        ProductionOrderPartialReleaseEntity sibling = fx.release(order, 2);
        CustomerAccountEntryResponse charge = fx.charge(customer, order, sibling, null, "1968.00");
        fx.create(customer, creditRequest("PAYMENT", charge.getId(), "1968.00"));
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isFalse();
        assertThat(row.getChargeStatus()).isEqualTo("NONE");
        assertThat(fx.receivableRows(customer, shipment)).isEmpty();
        assertThat(shipmentDoc().getChargeStatus()).isEqualTo("COVERED");
        assertThat(lfDocument().getChargeStatus()).isEqualTo("COVERED");
        assertThat(lfDocument().getChargeEntryId()).isNull();

        fx.charge(customer, order, release, shipment, "1968.00");
        assertThat(fx.activeCharges(customer)).hasSize(2);
        assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("CHARGED");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): charge linked only by the parent ENVP vendor_shipment_number (no order id) "
            + "-> list still offers GENERAR CARGO and an order-level charge with the same ENVP number is accepted — "
            + "fix should treat it as the order's single charge and REJECT the second")
    void vendorShipmentNumberOnlyCharge_isInvisibleAndAllowsDuplicate() throws Exception {
        CustomerAccountEntryRequest vendorOnly = chargeRequest(null, null, null, "1783.00");
        vendorOnly.setVendorShipmentNumber(order.getVendorShipmentNumber());
        CustomerAccountEntryResponse first = fx.create(customer, vendorOnly);
        payInFull(first.getId());
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isFalse();
        assertThat(row.getChargeStatus()).isEqualTo("NONE");

        CustomerAccountEntryResponse second = fx.charge(customer, order, null, null, "1968.00");

        assertThat(fx.activeCharges(customer)).hasSize(2)
                .extracting(CustomerAccountEntryEntity::getVendorShipmentNumber)
                .containsOnly("ENVP-OPC-T0028");
        assertThat(fx.entry(second.getId()).getProductionOrderId()).isEqualTo(order.getId());
        assertThat(fx.balance(customer)).isEqualByComparingTo("1968.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): a CHARGE with no productionOrderId is accepted and stored without an order "
            + "— fix should REJECT it (every charge needs production_order_id)")
    void chargeWithoutProductionOrderIsAccepted() throws Exception {
        CustomerAccountEntryResponse charge = fx.create(customer, chargeRequest(null, null, null, "1783.00"));

        CustomerAccountEntryEntity stored = fx.entry(charge.getId());
        assertThat(stored.getStatus()).isEqualTo("ACTIVE");
        assertThat(stored.getProductionOrderId()).isNull();
        assertThat(fx.balance(customer)).isEqualByComparingTo("1783.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): charge on the shipment id but without its partial_release_id -> list "
            + "offers GENERAR CARGO, cartera hides the shipment slot as COVERED and lists the charge only as an "
            + "orphan row, and createEntry rejects the charge the list offers — fix should key charges to the order "
            + "and report its shipments as covered everywhere")
    void shipmentChargeWithoutPartialRelease_listAndGuardDisagree() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, shipment, "1783.00");
        payInFull(charge.getId());

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isFalse();
        assertThat(row.getChargeStatus()).isEqualTo("NONE");

        assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(r -> {
            assertThat(r.getDocumentLevel()).isEqualTo("SHIPMENT");
            assertThat(r.getPartialReleaseId()).isNull();
            assertThat(r.getChargeEntryId()).isEqualTo(charge.getId());
        });

        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR: createEntry rejects a Q0 charge, but a legacy ACTIVE Q0 charge counts as PAID "
            + "in both read models and blocks a real charge on the shipment")
    void zeroAmountCharge() throws Exception {
        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "0.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("monto válido");

        fx.insert(legacyCharge(customer, order, release, shipment, "0.00").build());

        OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
        assertThat(row.isHasCharge()).isTrue();
        assertThat(row.getChargeStatus()).isEqualTo("PAID");
        assertThat(fx.receivableRows(customer, shipment))
                .singleElement()
                .extracting(CustomerAccountReceivableSearchResponse::getChargeStatus)
                .isEqualTo("PAID");
        assertThatThrownBy(() -> fx.charge(customer, order, release, shipment, "1968.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): two ACTIVE charges on one shipment make the shipments list throw "
            + "(Optional findActiveCharge) while cartera shows two rows — fix should prevent the duplicate")
    void twoActiveChargesOnOneShipment() {
        Long firstId = fx.insert(legacyCharge(customer, order, release, shipment, "1783.00").build()).getId();
        Long secondId = fx.insert(legacyCharge(customer, order, release, shipment, "1783.00").build()).getId();

        assertThatThrownBy(() -> fx.catalogRows(customer))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);

        assertThat(fx.receivableRows(customer, shipment))
                .extracting(CustomerAccountReceivableSearchResponse::getChargeEntryId)
                .containsExactlyInAnyOrder(firstId, secondId);
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), true).stream()
                .filter(d -> d.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }

    private LfShipmentDocumentResponse shipmentDoc() throws Exception {
        return lfDocument().getPartialReleases().stream()
                .flatMap(partial -> partial.getShipments().stream())
                .filter(doc -> shipment.getId().equals(doc.getProductShipmentId()))
                .findFirst()
                .orElseThrow();
    }

    /** Exact text of preventDuplicateCharge for this order's ENVP number. */
    private static String duplicateOf(String amount, String balance) {
        return "Este documento ya está cubierto por el cargo ENVP-OPC-T0028 (Q " + amount
                + ", saldo Q " + balance + "). Anúlelo si necesita registrarlo de nuevo.";
    }
}
