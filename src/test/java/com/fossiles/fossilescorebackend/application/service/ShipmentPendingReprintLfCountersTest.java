package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
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
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentPendingReprintLfCountersTest {

    @Autowired private ProductionOrderPartialReleaseService partialReleaseService;
    @Autowired private ProductDistributionService distributionService;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryLocationTypeRepository locationTypes;
    @Autowired private ProductShipmentRepository shipments;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(11L);
        color = colors.save(ColorEntity.builder().name("NEGRO-CNT").build());
        locationTypes.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        locations.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
    }

    @Test
    void pendingExcludesShippedAndRejectsExcess_voidReturnsQty() throws Exception {
        ProductEntity product = saveProduct("PEND-A");
        ProductionOrderEntity order = saveOrder("OPV-PEND-1", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity item = saveItem(order, product, 10);

        PartialReleaseResponse release = partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("P1")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(item.getId())
                                .quantity(3)
                                .build()))
                        .build());
        ProductShipmentResponse shipment = partialReleaseService.generateShipment(
                release.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Ciudad").build());
        assertThat(shipment.getShipmentNumber()).matches("ENVP-\\d{5}");

        PartialReleaseListResponse afterShip = partialReleaseService.listForOrder(order.getId());
        PartialReleaseLineResponse avail = availabilityFor(afterShip, item.getId());
        assertThat(avail.getPendingTotal()).isEqualTo(7);

        assertThatThrownBy(() -> partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("P2")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(item.getId())
                                .quantity(8)
                                .build()))
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendiente");

        distributionService.cancelShipment(shipment.getId());
        // El envío anulado deja de contar; el parcial vuelve a CONFIRMED y sigue reservando 3.
        PartialReleaseLineResponse afterVoid = availabilityFor(
                partialReleaseService.listForOrder(order.getId()), item.getId());
        assertThat(afterVoid.getPendingTotal()).isEqualTo(7);

        ProductShipmentResponse extra = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Extra")
                        .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(product.getId())
                                .colorId(color.getId())
                                .quantity(new BigDecimal("2"))
                                .build()))
                        .build());
        assertThat(availabilityFor(partialReleaseService.listForOrder(order.getId()), item.getId())
                .getPendingTotal()).isEqualTo(5);
        distributionService.cancelShipment(extra.getId());
        assertThat(availabilityFor(partialReleaseService.listForOrder(order.getId()), item.getId())
                .getPendingTotal()).isEqualTo(7);
    }

    @Test
    void reprintReturnsPersistedEditsAndShipmentNumber() throws Exception {
        ProductEntity product = saveProduct("REPR-A");
        ProductionOrderEntity order = saveOrder("OPV-REPR-1", "MARCAS", "Luis Felipe");
        saveItem(order, product, 4);

        ProductShipmentResponse created = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Showroom")
                        .documentDate("2026-10-01")
                        .notes("obs")
                        .shippingCost(new BigDecimal("15.50"))
                        .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(product.getId())
                                .colorId(color.getId())
                                .quantity(new BigDecimal("2"))
                                .size("M")
                                .hardwareCondition("NUEVO")
                                .unitPrice(new BigDecimal("99.00"))
                                .build()))
                        .build());
        assertThat(created.getShipmentNumber()).matches("ENVP-\\d{5}");

        ProductShipmentResponse updated = distributionService.updateShipmentProducts(
                created.getId(),
                List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(product.getId())
                        .colorId(color.getId())
                        .quantity(new BigDecimal("2"))
                        .size("L")
                        .hardwareCondition("VIEJO")
                        .unitPrice(new BigDecimal("80.25"))
                        .build()));
        ProductShipmentDetailResponse line = updated.getProducts().get(0);
        assertThat(line.getSize()).isEqualTo("L");
        assertThat(line.getHardwareCondition()).isEqualTo("VIEJO");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("80.25");

        ProductShipmentResponse loaded = distributionService.getShipmentById(created.getId());
        assertThat(loaded.getShipmentNumber()).isEqualTo(created.getShipmentNumber());
        assertThat(loaded.getNotes()).contains("DOCUMENT_DATE:2026-10-01");
        assertThat(loaded.getShippingCost()).isEqualByComparingTo("15.50");
        ProductShipmentDetailResponse stored = loaded.getProducts().get(0);
        assertThat(stored.getSize()).isEqualTo("L");
        assertThat(stored.getHardwareCondition()).isEqualTo("VIEJO");
        assertThat(stored.getUnitPrice()).isEqualByComparingTo("80.25");
    }

    @Test
    void lfCinchoGetsEnvpOnOrderAndOpcShipmentNumber() throws Exception {
        ProductEntity product = saveProduct("CIN-A");
        ProductionOrderEntity order = saveOrder("OPC-CIN-1", "CINCHOS", "Luis Felipe");
        saveItem(order, product, 2);

        ProductShipmentResponse shipment = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Bodega cliente")
                        .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(product.getId())
                                .colorId(color.getId())
                                .quantity(BigDecimal.ONE)
                                .build()))
                        .build());

        ProductionOrderEntity reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getVendorShipmentNumber()).matches("ENVP-\\d{5}");
        assertThat(shipment.getShipmentNumber()).matches(
                "^" + Pattern.quote(reloaded.getCode().trim().toUpperCase()) + "-ENV-\\d{5}$");
        assertThat(shipment.getShipmentNumber()).doesNotStartWith("ENVP-");
    }

    @Test
    void kioskGeneratorUnchanged() throws Exception {
        String kioskCode = "KIOSKO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocationEntity kiosk = locations.save(LocationEntity.builder()
                .code(kioskCode).name("Kiosko counter").categoria("KIOSKO").build());
        ProductEntity product = saveProduct("KIOSK-A");
        ProductionOrderEntity order = saveOrder("OPCK-CNT-1", "CLIENTE_KIOSKO", "Otro vendedor");
        saveItem(order, product, 2);
        shipments.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .locationId(kiosk.getId())
                .shipmentNumber(kioskCode + "-ENV-00001")
                .status("CONFIRMED")
                .build());

        ProductShipmentResponse next = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .locationId(kiosk.getId())
                        .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(product.getId())
                                .colorId(color.getId())
                                .quantity(BigDecimal.ONE)
                                .build()))
                        .build());

        assertThat(next.getShipmentNumber()).isEqualTo(kioskCode + "-ENV-00002");
        assertThat(next.getShipmentNumber()).doesNotStartWith("ENVP-");
    }

    private PartialReleaseLineResponse availabilityFor(PartialReleaseListResponse list, Long itemId) {
        return list.getOrderItemAvailability().stream()
                .filter(row -> itemId.equals(row.getProductionOrderItemId()))
                .findFirst()
                .orElseThrow();
    }

    private ProductionOrderEntity saveOrder(String code, String orderType, String sellerName) {
        return orders.save(ProductionOrderEntity.builder()
                .code(code + "-" + UUID.randomUUID().toString().substring(0, 8))
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(sellerName)
                .customerName("Cliente CNT")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(ProductionOrderEntity order, ProductEntity product, int quantity) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductEntity saveProduct(String code) {
        return products.save(ProductEntity.builder()
                .code(code + "-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }
}
