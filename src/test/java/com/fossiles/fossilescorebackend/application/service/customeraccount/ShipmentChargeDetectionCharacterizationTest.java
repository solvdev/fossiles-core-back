package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
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
                .hasMessageContaining("ya está cubierto por el cargo")
                .hasMessageContaining("saldo Q 0.00")
                .hasMessageContaining("Anúlelo si necesita registrarlo de nuevo");
        assertThat(fx.activeCharges(customer)).hasSize(1);
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
}
