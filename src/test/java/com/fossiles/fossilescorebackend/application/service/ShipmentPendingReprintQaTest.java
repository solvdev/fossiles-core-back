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
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Coverage that Baku's ShipmentPendingReprintLfCountersTest does not already assert.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentPendingReprintQaTest {

    @Autowired private ProductionOrderPartialReleaseService partialReleaseService;
    @Autowired private ProductDistributionService distributionService;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryLocationTypeRepository locationTypes;
    @Autowired private MaterialRepository materials;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(21L);
        color = colors.save(ColorEntity.builder().name("QA-NEG-" + tag()).build());
        if (locationTypes.findByCode("BODEGA_PT").isEmpty()) {
            locationTypes.save(InventoryLocationTypeEntity.builder()
                    .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        }
        if (locations.findByCode("BODEGA_PT").isEmpty()) {
            locations.save(LocationEntity.builder()
                    .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
        }
    }

    @Test
    void sequentialPartials_order10_ship4_pending6_ship3_pending3() throws Exception {
        ProductEntity product = saveProduct("SEQ");
        ProductionOrderEntity order = saveOrder("OPV-SEQ", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity item = saveItem(order, product, color, 10, null);

        shipPartial(order, item, 4, "P1");
        assertThat(pendingTotal(order, item)).isEqualTo(6);

        shipPartial(order, item, 3, "P2");
        assertThat(pendingTotal(order, item)).isEqualTo(3);

        assertThatThrownBy(() -> createConfirmedPartial(order, item, 4, "P3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendiente");
    }

    @Test
    void pendingIsTrackedPerProductColorAndSize() throws Exception {
        ColorEntity colorB = colors.save(ColorEntity.builder().name("QA-AZ-" + tag()).build());
        ProductEntity sized = saveProduct("SZ");
        ProductEntity bulk = saveProduct("BK");
        ProductionOrderEntity order = saveOrder("OPV-SZ", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity sizedItem = saveItem(order, sized, color, 10, "{\"38\":4,\"39\":6}");
        ProductionOrderItemEntity bulkItem = saveItem(order, bulk, colorB, 8, null);

        distributionService.createOrUpdateShipmentForProductionOrder(order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Showroom")
                        .products(List.of(
                                line(sized, color, "38", "2"),
                                line(bulk, colorB, null, "3")))
                        .build());

        PartialReleaseListResponse list = partialReleaseService.listForOrder(order.getId());
        PartialReleaseLineResponse sizedAvail = availabilityFor(list, sizedItem.getId());
        PartialReleaseLineResponse bulkAvail = availabilityFor(list, bulkItem.getId());

        assertThat(sizedAvail.getPendingTotal()).isEqualTo(8);
        assertThat(sizedAvail.getPendingSizes()).containsEntry("38", 2).containsEntry("39", 6);
        assertThat(bulkAvail.getPendingTotal()).isEqualTo(5);

        assertThatThrownBy(() -> partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("over-size")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(sizedItem.getId())
                                .sizes(Map.of("38", 3))
                                .build()))
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendiente");
    }

    @Test
    void regularShipmentReducesPendingAndVoidReturnsQuantity() throws Exception {
        ProductEntity product = saveProduct("VOID");
        ProductionOrderEntity order = saveOrder("OPV-VOID", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity item = saveItem(order, product, color, 10, null);

        ProductShipmentResponse shipment = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Showroom")
                        .products(List.of(line(product, color, null, "4")))
                        .build());
        assertThat(pendingTotal(order, item)).isEqualTo(6);

        distributionService.cancelShipment(shipment.getId());
        assertThat(pendingTotal(order, item)).isEqualTo(10);
    }

    @Test
    void fullyShippedLineIsExcludedFromPositiveAvailability() throws Exception {
        ProductEntity shippedProduct = saveProduct("FULL");
        ProductEntity openProduct = saveProduct("OPEN");
        ProductionOrderEntity order = saveOrder("OPV-FULL", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity shippedItem = saveItem(order, shippedProduct, color, 5, null);
        ProductionOrderItemEntity openItem = saveItem(order, openProduct, color, 5, null);

        distributionService.createOrUpdateShipmentForProductionOrder(order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Showroom")
                        .products(List.of(line(shippedProduct, color, null, "5")))
                        .build());

        PartialReleaseListResponse list = partialReleaseService.listForOrder(order.getId());
        assertThat(availabilityFor(list, shippedItem.getId()).getPendingTotal()).isZero();
        assertThat(list.getOrderItemAvailability())
                .filteredOn(row -> row.getPendingTotal() != null && row.getPendingTotal() > 0)
                .extracting(PartialReleaseLineResponse::getProductionOrderItemId)
                .containsExactly(openItem.getId());

        assertThatThrownBy(() -> createConfirmedPartial(order, shippedItem, 1, "too-much"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendiente");
    }

    @Test
    void reprintPersistsPackingAndSurvivesSendAndOrderPriceChange() throws Exception {
        ProductEntity product = saveProduct("REPR");
        ProductionOrderEntity order = saveOrder("OPV-REPR", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity item = saveItem(order, product, color, 4, null);
        MaterialEntity packing = materials.save(MaterialEntity.builder()
                .sku("SUM-" + tag())
                .name("Caja QA")
                .build());

        ProductShipmentResponse created = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(),
                ProductShipmentRequest.builder()
                        .destinationAddress("Showroom")
                        .documentDate("2026-10-02")
                        .notes("obs-qa")
                        .shippingCost(new BigDecimal("12.00"))
                        .packingItems(List.of(ProductShipmentRequest.PackingItemRequest.builder()
                                .materialId(packing.getId())
                                .quantity(new BigDecimal("2"))
                                .unitPrice(new BigDecimal("5.00"))
                                .build()))
                        .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(product.getId())
                                .colorId(color.getId())
                                .quantity(new BigDecimal("2"))
                                .size("M")
                                .hardwareCondition("NUEVO")
                                .unitPrice(new BigDecimal("99.00"))
                                .build()))
                        .build());

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
        assertReprintLine(updated.getProducts().get(0));
        assertPackingAndHeader(updated, packing.getId());

        ProductShipmentResponse loaded = distributionService.getShipmentById(created.getId());
        assertReprintLine(loaded.getProducts().get(0));
        assertPackingAndHeader(loaded, packing.getId());

        distributionService.confirmShipmentDraft(created.getId());
        ProductShipmentResponse sent = distributionService.sendShipment(created.getId());
        assertThat(sent.getStatus()).isEqualToIgnoringCase("SENT");
        ProductShipmentResponse afterSend = distributionService.getShipmentById(created.getId());
        assertReprintLine(afterSend.getProducts().get(0));
        assertPackingAndHeader(afterSend, packing.getId());

        item.setUnitPrice(new BigDecimal("250.00"));
        items.save(item);
        ProductShipmentResponse afterPriceChange = distributionService.getShipmentById(created.getId());
        assertReprintLine(afterPriceChange.getProducts().get(0));
        assertPackingAndHeader(afterPriceChange, packing.getId());
    }

    private void assertReprintLine(ProductShipmentDetailResponse line) {
        assertThat(line.getSize()).isEqualTo("L");
        assertThat(line.getHardwareCondition()).isEqualTo("VIEJO");
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("80.25");
    }

    private void assertPackingAndHeader(ProductShipmentResponse shipment, Long materialId) {
        assertThat(shipment.getNotes()).contains("DOCUMENT_DATE:2026-10-02");
        assertThat(shipment.getShippingCost()).isEqualByComparingTo("12.00");
        assertThat(shipment.getPackingItems()).hasSize(1);
        assertThat(shipment.getPackingItems().get(0).getMaterialId()).isEqualTo(materialId);
        assertThat(shipment.getPackingItems().get(0).getQuantity()).isEqualByComparingTo("2");
    }

    private void shipPartial(ProductionOrderEntity order, ProductionOrderItemEntity item, int qty, String label)
            throws Exception {
        PartialReleaseResponse release = createConfirmedPartial(order, item, qty, label);
        partialReleaseService.generateShipment(
                release.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Ciudad").build());
    }

    private PartialReleaseResponse createConfirmedPartial(
            ProductionOrderEntity order, ProductionOrderItemEntity item, int qty, String label) throws Exception {
        return partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label(label)
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(item.getId())
                                .quantity(qty)
                                .build()))
                        .build());
    }

    private int pendingTotal(ProductionOrderEntity order, ProductionOrderItemEntity item) throws Exception {
        return availabilityFor(partialReleaseService.listForOrder(order.getId()), item.getId()).getPendingTotal();
    }

    private PartialReleaseLineResponse availabilityFor(PartialReleaseListResponse list, Long itemId) {
        return list.getOrderItemAvailability().stream()
                .filter(row -> itemId.equals(row.getProductionOrderItemId()))
                .findFirst()
                .orElseThrow();
    }

    private ProductShipmentRequest.ProductShipmentDetailRequest line(
            ProductEntity product, ColorEntity lineColor, String size, String qty) {
        return ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                .productId(product.getId())
                .colorId(lineColor.getId())
                .size(size)
                .quantity(new BigDecimal(qty))
                .build();
    }

    private ProductionOrderEntity saveOrder(String code, String orderType, String sellerName) {
        return orders.save(ProductionOrderEntity.builder()
                .code(code + "-" + tag())
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(sellerName)
                .customerName("Cliente QA")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(
            ProductionOrderEntity order, ProductEntity product, ColorEntity itemColor, int quantity, String sizesData) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(itemColor.getId())
                .quantity(quantity)
                .sizesData(sizesData)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductEntity saveProduct(String code) {
        return products.save(ProductEntity.builder()
                .code(code + "-" + tag())
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
