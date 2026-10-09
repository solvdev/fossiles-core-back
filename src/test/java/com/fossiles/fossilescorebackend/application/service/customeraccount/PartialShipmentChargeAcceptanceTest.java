package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfPartialReleaseDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfSalesDocumentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LfShipmentDocumentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_ORDER_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.PARTIAL_HAS_NO_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.legacyCharge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CB116-like order: products Q16,131 across two partial shipments. One active charge for the order.
 */
class PartialShipmentChargeAcceptanceTest extends LfReceivablesH2TestBase {

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

    private void shipPartial2() {
        release2 = fx.release(order, 2);
        shipment2 = fx.shipment(order, release2, "ENVP-90124-ENV-00002", "947.00");
    }

    @Test
    @DisplayName("A charge on partial 2 is rejected; the order charge covers both shipments")
    void chargeOnSecondPartialIsAccepted() throws Exception {
        shipPartial2();
        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "947.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "16131.00");

        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("16131.00");
        assertThat(fx.catalogRow(customer, shipment1).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment1).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.catalogRow(customer, shipment2).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment2).getChargeStatus()).isEqualTo("CHARGED");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("After a partial payment, a later shipment stays on the same charge and a second charge is rejected")
    void partiallyPaidFirstPartialThenSecondPartialShips() throws Exception {
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "15184.00");
        assertThat(fx.entry(charge.getId()).getAmount()).isEqualByComparingTo("16131.00");
        fx.create(customer, creditRequest("PAYMENT", charge.getId(), "5000.00"));
        shipPartial2();

        assertThat(fx.catalogRow(customer, shipment2).isHasCharge()).isTrue();
        assertThat(fx.catalogRow(customer, shipment2).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(fx.receivableRows(customer, shipment2)).singleElement().satisfies(row -> {
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeStatus()).isEqualTo("PARTIAL");
            assertThat(row.getChargeEntryId()).isEqualTo(charge.getId());
        });
        LfSalesDocumentResponse document = lfDocument();
        assertThat(partialDoc(document, release1).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(partialDoc(document, release1).getShipments())
                .extracting(LfShipmentDocumentResponse::getChargeStatus)
                .containsExactly("PARTIAL");
        assertThat(partialDoc(document, release2).getChargeStatus()).isEqualTo("PARTIAL");
        assertThat(partialDoc(document, release2).getShipments())
                .extracting(LfShipmentDocumentResponse::getChargeStatus)
                .containsExactly("PARTIAL");
        assertThat(document.getPartialReleases())
                .extracting(LfPartialReleaseDocumentResponse::getChargeStatus)
                .doesNotContain("COVERED", "NONE");

        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "947.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.create(customer, creditRequest("PAYMENT", charge.getId(), "11132.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El monto excede el saldo pendiente del documento (Q 11131.00).");
        assertThat(fx.activeCharges(customer)).hasSize(1);
        assertThat(fx.balance(customer)).isEqualByComparingTo("11131.00");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("11131.00");
    }

    @Test
    @DisplayName("A second charge on partial 1 is rejected")
    void secondChargeOnFirstPartialIsRejected() throws Exception {
        fx.charge(customer, order, null, null, "15184.00");

        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE)
                .hasMessageNotContaining("ya está cubierto")
                .hasMessageNotContaining("Anúlelo");
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("A partial cannot carry the whole-order amount; the order charge is the product total")
    void partialChargeAmountIsNotValidatedAgainstEstimate() throws Exception {
        shipPartial2();
        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "16131.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);

        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "16131.00");
        assertThat(charge.getAmount()).isEqualByComparingTo("16131.00");
        assertThat(fx.catalogRow(customer, shipment2).isHasCharge()).isTrue();
        assertThat(fx.receivableRows(customer, shipment2)).singleElement().satisfies(row -> {
            assertThat(row.isHasCharge()).isTrue();
            assertThat(row.getChargeStatus()).isNotEqualTo("COVERED");
        });
        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "947.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThat(fx.balance(customer)).isEqualByComparingTo("16131.00");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("With an order charge, catalog, cartera and lf-documents all report that charge")
    void orderLevelCharge_partialRowsPerReadModel() throws Exception {
        shipPartial2();
        CustomerAccountEntryResponse orderCharge = fx.charge(customer, order, null, null, "16131.00");

        for (ProductShipmentEntity shipment : List.of(shipment1, shipment2)) {
            assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();
            assertThat(fx.catalogRow(customer, shipment).getChargeStatus()).isEqualTo("CHARGED");
            assertThat(fx.receivableRows(customer, shipment)).singleElement().satisfies(row -> {
                assertThat(row.isHasCharge()).isTrue();
                assertThat(row.getChargeEntryId()).isEqualTo(orderCharge.getId());
                assertThat(row.getChargeStatus()).isEqualTo("CHARGED");
            });
        }

        LfSalesDocumentResponse document = lfDocument();
        assertThat(document.getChargeStatus()).isEqualTo("CHARGED");
        assertThat(document.getChargeEntryId()).isEqualTo(orderCharge.getId());
        assertThat(document.getPartialReleases()).hasSize(2).allSatisfy(partial -> {
            assertThat(partial.getChargeStatus()).isEqualTo("CHARGED");
            assertThat(partial.getChargeEntryId()).isEqualTo(orderCharge.getId());
            assertThat(partial.getShipments())
                    .extracting(LfShipmentDocumentResponse::getChargeStatus)
                    .containsOnly("CHARGED");
        });
    }

    @Test
    @DisplayName("An order charge blocks further charges on the same order")
    void orderLevelCharge_blocksFurtherCharges() throws Exception {
        shipPartial2();
        fx.charge(customer, order, null, null, "16131.00");

        assertThatThrownBy(() -> fx.charge(customer, order, release1, shipment1, "15184.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.charge(customer, order, release2, shipment2, "947.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PARTIAL_HAS_NO_CHARGE);
        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "16131.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE);
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    @Test
    @DisplayName("Legacy per-shipment charges still block an order-level charge")
    void orderLevelNullShipmentNextToPerShipmentChargesIsRejected() throws Exception {
        shipPartial2();
        fx.insert(legacyCharge(customer, order, release1, shipment1, "15184.00").build());
        fx.insert(legacyCharge(customer, order, release2, shipment2, "947.00").build());

        assertThatThrownBy(() -> fx.charge(customer, order, null, null, "16131.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(DUPLICATE_ORDER_CHARGE)
                .hasMessageNotContaining("Anúlelo");
        assertThat(fx.activeCharges(customer)).hasSize(2);
        assertThat(fx.activeCharges(customer))
                .allSatisfy(charge -> assertThat(charge.getProductShipmentId()).isNotNull());
    }

    private LfSalesDocumentResponse lfDocument() throws Exception {
        return fx.accounts.getLfDocuments(customer.getId(), true).stream()
                .filter(doc -> doc.getProductionOrderId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static LfPartialReleaseDocumentResponse partialDoc(
            LfSalesDocumentResponse document, ProductionOrderPartialReleaseEntity release) {
        return document.getPartialReleases().stream()
                .filter(partial -> release.getId().equals(partial.getPartialReleaseId()))
                .findFirst()
                .orElseThrow();
    }
}
