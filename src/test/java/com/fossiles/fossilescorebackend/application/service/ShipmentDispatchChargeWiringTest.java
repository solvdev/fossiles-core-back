package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentShippingCostRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The receivable is born when a shipment is dispatched: {@code sendShipment} creates its charge, and reversing,
 * cancelling or editing the dispatched shipment keeps the charge in step. Real services on H2.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentDispatchChargeWiringTest {

    @Autowired private ProductDistributionService distribution;
    @Autowired private CustomerAccountService accounts;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductShipmentRepository shipments;
    @Autowired private ProductShipmentDetailRepository details;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private ProductInventoryLocationRepository inventory;
    @Autowired private InventoryLocationTypeRepository locationTypes;
    @Autowired private LocationRepository locations;
    @Autowired private CustomerAccountEntryRepository entries;

    @MockBean private SecurityUtil securityUtil;

    private CustomerEntity customer;
    private ProductionOrderEntity order;
    private ProductShipmentEntity shipment;
    private ProductEntity product;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(7L);
        locationTypes.save(InventoryLocationTypeEntity.builder().code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        LocationEntity bodega = locations.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
        LocationEntity kiosko = locations.save(LocationEntity.builder()
                .code("KIOSKO-01").name("Kiosko Cayala").categoria("KIOSKO").build());
        color = colors.save(ColorEntity.builder().name("NEGRO").build());
        product = products.save(ProductEntity.builder().code("PT-CHARGE-01").name("Producto").requiresMaterials(false).build());
        inventory.save(ProductInventoryLocation.builder()
                .productId(product.getId()).locationId(bodega.getId()).colorId(color.getId())
                .quantity(BigDecimal.valueOf(100)).build());

        customer = customers.save(CustomerEntity.builder().name("Cliente despacho").legacyCode("DSP-1").status("ACTIVE").build());
        order = orders.save(ProductionOrderEntity.builder()
                .code("OPV-DSP-1").orderType("OPV").customerId(customer.getId()).sellerName("LUIS FELIPE")
                .vendorShipmentNumber("ENVP-DSP-1").status("IN_PROGRESS").startDate(LocalDate.of(2026, 9, 1)).build());
        items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId()).productId(product.getId()).colorId(color.getId())
                .quantity(10).unitPrice(new BigDecimal("50.00")).build());
        shipment = shipments.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId()).shipmentNumber("ENVP-DSP-1-ENV-1")
                .status("CONFIRMED").locationId(kiosko.getId()).build());
        details.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId()).productId(product.getId()).colorId(color.getId())
                .sizeLabel("").quantity(BigDecimal.valueOf(4)).unitPrice(new BigDecimal("50.00")).build());
    }

    private List<CustomerAccountEntryEntity> activeEntries(String type) {
        return entries.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customer.getId(), "ACTIVE").stream()
                .filter(e -> type.equals(e.getEntryType()))
                .toList();
    }

    @Test
    void dispatchCreatesTheChargeForWhatWasSent() throws Exception {
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("0");

        distribution.sendShipment(shipment.getId());

        assertThat(activeEntries("CHARGE")).singleElement().satisfies(charge -> {
            assertThat(charge.getProductShipmentId()).isEqualTo(shipment.getId());
            assertThat(charge.getAmount()).isEqualByComparingTo("200.00");
            assertThat(charge.getInvoiceNumber()).isEqualTo("ENVP-DSP-1");
            assertThat(charge.getEntryDate()).isNotNull();
        });
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void editingAndRevertingTheDispatchedShipmentKeepsTheChargeInStep() throws Exception {
        distribution.sendShipment(shipment.getId());

        distribution.updateShipmentProducts(shipment.getId(), List.of(
                ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(product.getId()).colorId(color.getId())
                        .quantity(BigDecimal.valueOf(6)).unitPrice(new BigDecimal("50.00")).build()));
        assertThat(activeEntries("CHARGE")).singleElement()
                .satisfies(charge -> assertThat(charge.getAmount()).isEqualByComparingTo("300.00"));

        ProductShipmentShippingCostRequest shipping = new ProductShipmentShippingCostRequest();
        shipping.setShippingCost(new BigDecimal("25.00"));
        distribution.updateShipmentShippingCost(shipment.getId(), shipping);
        assertThat(activeEntries("CHARGE_ADJUSTMENT")).singleElement()
                .satisfies(adjustment -> assertThat(adjustment.getAmount()).isEqualByComparingTo("25.00"));
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("325.00");

        distribution.revertSentShipment(shipment.getId());

        assertThat(activeEntries("CHARGE")).isEmpty();
        assertThat(activeEntries("CHARGE_ADJUSTMENT")).isEmpty();
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void cancellingTheDispatchedShipmentVoidsItsCharge() throws Exception {
        distribution.sendShipment(shipment.getId());
        assertThat(activeEntries("CHARGE")).hasSize(1);

        distribution.cancelShipment(shipment.getId());

        assertThat(activeEntries("CHARGE")).isEmpty();
        assertThat(accounts.getBalance(customer.getId()).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void aShipmentWithPaymentsCannotBeCancelledOrReverted() throws Exception {
        distribution.sendShipment(shipment.getId());
        CustomerAccountEntryEntity charge = activeEntries("CHARGE").get(0);
        entries.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId()).entryType("PAYMENT").status("ACTIVE")
                .entryDate(LocalDate.of(2026, 9, 2)).amount(new BigDecimal("50.00"))
                .grossCollectedAmount(new BigDecimal("50.00")).appliedToEntryId(charge.getId())
                .productionOrderId(order.getId()).build());

        assertThatThrownBy(() -> distribution.cancelShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pagos, notas de crédito o devoluciones");
        assertThatThrownBy(() -> distribution.revertSentShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pagos, notas de crédito o devoluciones");
        assertThat(shipments.findById(shipment.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(activeEntries("CHARGE")).hasSize(1);
    }
}
