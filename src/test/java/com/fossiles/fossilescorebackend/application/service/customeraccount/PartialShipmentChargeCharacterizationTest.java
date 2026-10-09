package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfPartialReleaseDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfShipmentDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Case 2 (CB116-like, ENVP-00124): one OPV order (products Q16,131) with two partial shipments,
 * ENV-00001 (Q15,184) and ENV-00002 (Q947). Target rule: one active CHARGE per production order, keyed to
 * the parent ENVP number. Shipping for a later partial would be a CHARGE_ADJUSTMENT, which does not exist
 * yet and is not used here. Partial shipments never get their own charge.
 */
class PartialShipmentChargeCharacterizationTest extends LfReceivablesH2TestBase {

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductionOrderPartialReleaseEntity release1;
    private ProductionOrderPartialReleaseEntity release2;
    private ProductShipmentEntity shipment1;
    private ProductShipmentEntity shipment2;

    @BeforeEach
    void setUpOrder() {
        customer = fx.customer("CB116-T");
        order = fx.order(customer, "OPV-T0124", TYPE_OPV, "15184.00", "947.00");
        release1 = fx.release(order, 1);
        shipment1 = fx.shipment(order, release1, "ENVP-90124-ENV-00001", "15184.00");
    }

    /** Partial 2 exists only once it has actually shipped. */
    private void shipPartial2() {
        release2 = fx.release(order, 2);
        shipment2 = fx.shipment(order, release2, "ENVP-90124-ENV-00002", "947.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): with partial 1 charged, partial 2 still offers GENERAR CARGO and its own "
            + "charge is accepted (two charges on one order) — fix should REJECT the partial-2 charge")
    void chargeOnSecondPartialIsAccepted() throws Exception {
        shipPartial2();
        fx.charge(customer, order, release1, shipment1, "15184.00");

        assertThat(fx.catalogRow(customer, shipment1).getChargeStatus()).isEqualTo("CHARGED");
        OpvShipmentCatalogRowResponse partial2 = fx.catalogRow(customer, shipment2);
        assertThat(partial2.isHasCharge()).isFalse();
        assertThat(partial2.getEstimatedTotal()).isEqualByComparingTo("947.00");

        fx.charge(customer, order, release2, shipment2, "947.00");

        assertThat(fx.catalogRow(customer, shipment2).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.activeCharges(customer)).hasSize(2);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): partial 1 charged and partly paid, then partial 2 ships -> every read model "
            + "offers partial 2 a charge and a second CHARGE is accepted; payments stay capped per charge — target: "
            + "one charge per order plus a linked debit adjustment per real partial, payments linked to the order's "
            + "charge and capped at charge + adjustments - credits")
    void partiallyPaidFirstPartialThenSecondPartialShips() throws Exception {
        CustomerAccountEntryResponse charge1 = fx.charge(customer, order, release1, shipment1, "15184.00");
        fx.create(customer, creditRequest("PAYMENT", charge1.getId(), "5000.00"));
        shipPartial2();

        OpvShipmentCatalogRowResponse catalogPartial2 = fx.catalogRow(customer, shipment2);
        assertThat(catalogPartial2.isHasCharge()).isFalse();
        assertThat(catalogPartial2.getChargeStatus()).isEqualTo("NONE");
        assertThat(fx.receivableRows(customer, shipment2)).singleElement().satisfies(r -> {
            assertThat(r.getChargeStatus()).isEqualTo("NONE");
            assertThat(r.isHasCharge()).isFalse();
            assertThat(r.getEstimatedTotal()).isEqualByComparingTo("947.00");
        });
        LfSalesDocumentResponse document = lfDocument();
        assertThat(partialDoc(document, release1).getChargeStatus()).isEqualTo("COVERED");
        assertThat(partialDoc(document, release1).getShipments())
                .extracting(LfShipmentDocumentResponse::getChargeStatus)
                .containsExactly("PARTIAL");
        assertThat(partialDoc(document, release2).getChargeStatus()).isEqualTo("NONE");
        assertThat(partialDoc(document, release2).getShipments())
                .extracting(LfShipmentDocumentResponse::getChargeStatus)
                .containsExactly("NONE");

        CustomerAccountEntryResponse charge2 = fx.charge(customer, order, release2, shipment2, "947.00");

        assertThat(fx.activeCharges(customer)).hasSize(2);
        assertThat(fx.balance(customer)).isEqualByComparingTo("11131.00");
        assertThat(fx.chargeBalance(customer, charge1.getId())).isEqualByComparingTo("10184.00");
        assertThat(fx.chargeBalance(customer, charge2.getId())).isEqualByComparingTo("947.00");
        assertThatThrownBy(() -> fx.create(customer, creditRequest("PAYMENT", charge1.getId(), "11131.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto excede el saldo pendiente del documento (Q 10184.00).");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (matches target): a second charge on partial 1 is rejected")
    void secondChargeOnFirstPartialIsRejected() throws Exception {
        fx.charge(customer, order, release1, shipment1, "15184.00");

        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): the amount is client-supplied; a partial-1 charge for the whole order is "
            + "accepted, cartera then hides partial 2 as COVERED while the list still offers GENERAR CARGO and a "
            + "partial-2 charge is accepted — fix should REJECT any charge on a partial")
    void partialChargeAmountIsNotValidatedAgainstEstimate() throws Exception {
        shipPartial2();
        assertThat(fx.catalogRow(customer, shipment1).getEstimatedTotal()).isEqualByComparingTo("15184.00");
        assertThat(fx.receivableRows(customer, shipment1)).singleElement()
                .satisfies(r -> assertThat(r.getEstimatedTotal()).isEqualByComparingTo("15184.00"));

        CustomerAccountEntryResponse wholeOrderOnPartial1 = fx.charge(customer, order, release1, shipment1, "16131.00");
        assertThat(wholeOrderOnPartial1.getAmount()).isEqualByComparingTo("16131.00");

        assertThat(fx.receivableRows(customer, shipment2)).isEmpty();
        assertThat(fx.catalogRow(customer, shipment2).isHasCharge()).isFalse();

        fx.charge(customer, order, release2, shipment2, "947.00");
        assertThat(fx.balance(customer)).isEqualByComparingTo("17078.00");
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): with an order-level charge, /lf-documents reports partials and shipments as "
            + "COVERED, /receivable-search drops them (COVERED rows are skipped) and lists the charge as an orphan "
            + "row, but the OPV shipments list still offers GENERAR CARGO — fix should show partials as covered there")
    void orderLevelCharge_partialRowsPerReadModel() throws Exception {
        shipPartial2();
        CustomerAccountEntryResponse orderCharge = fx.charge(customer, order, null, null, "16131.00");

        for (ProductShipmentEntity shipment : List.of(shipment1, shipment2)) {
            OpvShipmentCatalogRowResponse row = fx.catalogRow(customer, shipment);
            assertThat(row.isHasCharge()).isFalse();
            assertThat(row.getChargeStatus()).isEqualTo("NONE");
            assertThat(fx.receivableRows(customer, shipment)).isEmpty();
        }

        assertThat(fx.receivableRows(customer)).singleElement().satisfies(r -> {
            assertThat(r.getChargeEntryId()).isEqualTo(orderCharge.getId());
            assertThat(r.getDocumentLevel()).isEqualTo("CHARGE");
            assertThat(r.getProductShipmentId()).isNull();
        });

        LfSalesDocumentResponse document = lfDocument();
        assertThat(document.getChargeStatus()).isEqualTo("CHARGED");
        assertThat(document.getChargeEntryId()).isEqualTo(orderCharge.getId());
        assertThat(document.getPartialReleases())
                .hasSize(2)
                .allSatisfy(partial -> {
                    assertThat(partial.getChargeStatus()).isEqualTo("COVERED");
                    assertThat(partial.getShipments())
                            .extracting(LfShipmentDocumentResponse::getChargeStatus)
                            .containsOnly("COVERED");
                })
                .extracting(LfPartialReleaseDocumentResponse::getChargeEntryId)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (matches target, keep rejecting): with an order-level charge, per-shipment charges "
            + "and a second order-level charge on the same order are rejected")
    void orderLevelCharge_blocksFurtherCharges() throws Exception {
        shipPartial2();
        fx.charge(customer, order, null, null, "16131.00");

        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "947.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "16131.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está cubierto");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("CURRENT BEHAVIOR (matches target, keep rejecting): after per-shipment charges exist, an order-level "
            + "charge with NULL shipment and NULL partial is rejected by findOverlappingCharge")
    void orderLevelNullShipmentNextToPerShipmentChargesIsRejected() throws Exception {
        shipPartial2();
        fx.charge(customer, order, release1, shipment1, "15184.00");
        fx.charge(customer, order, release2, shipment2, "947.00");

        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "16131.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Este documento ya está cubierto por el cargo ENVP-OPV-T0124 "
                        + "(Q 15184.00, saldo Q 15184.00). Anúlelo si necesita registrarlo de nuevo.");
        assertThat(fx.activeCharges(customer)).hasSize(2);
        assertThat(fx.activeCharges(customer))
                .allSatisfy(charge -> assertThat(charge.getProductShipmentId()).isNotNull());
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), true).stream()
                .filter(d -> d.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static LfPartialReleaseDocumentResponse partialDoc(
            LfSalesDocumentResponse document, ProductionOrderPartialReleaseEntity release) {
        return document.getPartialReleases().stream()
                .filter(p -> release.getId().equals(p.getPartialReleaseId()))
                .findFirst()
                .orElseThrow();
    }
}
