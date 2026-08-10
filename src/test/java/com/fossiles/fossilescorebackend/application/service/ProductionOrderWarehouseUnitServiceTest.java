package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductionOrderWarehouseUnitServiceTest {

    @Autowired
    private ProductionOrderWarehouseUnitService warehouseUnitService;

    @Autowired
    private CustomerShipmentDispatchService customerShipmentDispatchService;

    @Autowired
    private ProductionOrderRepository productionOrderRepository;

    @Autowired
    private ProductionOrderItemRepository productionOrderItemRepository;

    @Autowired
    private ProductionOrderWarehouseUnitRepository unitRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private InventoryLocationTypeRepository inventoryLocationTypeRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ProductInventoryLocationRepository inventoryLocationRepository;

    @Autowired
    private ProductInventoryKardexRepository kardexRepository;

    @Autowired
    private OnlineSaleRepository onlineSaleRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProductShipmentRepository shipmentRepository;

    @Autowired
    private ProductShipmentDetailRepository shipmentDetailRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private ProductEntity product;
    private ColorEntity color;
    private LocationEntity bodegaPt;
    private Long userId = 99L;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(userId);

        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega PT")
                .isActive(true)
                .build());

        bodegaPt = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega Producto Terminado")
                .categoria("BODEGA_PT")
                .build());

        product = productRepository.save(ProductEntity.builder()
                .code("WH-PROD-01")
                .name("Producto bodega test")
                .requiresMaterials(false)
                .build());

        color = colorRepository.save(ColorEntity.builder()
                .name("NEGRO")
                .build());
    }

    @Test
    void generatesSixUnitsForQuantitySix() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-001");
        ProductionOrderItemEntity item = createItem(po, 6, null);

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());

        assertThat(workspace.getUnits()).hasSize(6);
        assertThat(workspace.getSummary().getTotalUnits()).isEqualTo(6);
        assertThat(workspace.getSummary().getPendingUnits()).isEqualTo(6);
        assertThat(unitRepository.findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId())).hasSize(6);
    }

    @Test
    void generatesUnitsFromSizesData() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-002");
        Map<String, Integer> sizes = Map.of("M", 2, "L", 4);
        createItem(po, 6, ProductInventorySizesJson.serialize(
                sizes.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> java.math.BigDecimal.valueOf(e.getValue())))));

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());

        assertThat(workspace.getUnits()).hasSize(6);
        long sizeM = workspace.getUnits().stream().filter(u -> "M".equals(u.getSizeKey())).count();
        long sizeL = workspace.getUnits().stream().filter(u -> "L".equals(u.getSizeKey())).count();
        assertThat(sizeM).isEqualTo(2);
        assertThat(sizeL).isEqualTo(4);
    }

    @Test
    void receiveAllUnitsUpdatesWarehouseQtyAndInventory() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-003");
        ProductionOrderItemEntity item = createItem(po, 6, null);
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());

        WarehouseUnitReceiptRequest request = WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build();

        warehouseUnitService.updateUnitsReceipt(po.getId(), request);

        ProductionOrderItemEntity refreshed = productionOrderItemRepository.findById(item.getId()).orElseThrow();
        assertThat(refreshed.getWarehouseReceivedQty()).isEqualTo(6);

        ProductInventoryLocation inv = inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), bodegaPt.getId(), color.getId())
                .orElseThrow();
        assertThat(inv.getQuantity().intValue()).isEqualTo(6);
    }

    @Test
    void rejectOneUnitCreatesReprocessTask() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-004");
        createItem(po, 3, null);
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        Long unitId = workspace.getUnits().get(0).getId();

        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(List.of(WarehouseUnitReceiptRequest.UnitUpdate.builder()
                        .unitId(unitId)
                        .receiptStatus("REJECTED")
                        .rejectionReason("Costura defectuosa")
                        .build()))
                .build());

        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(po.getId());
        assertThat(tasks).isNotEmpty();
        assertThat(tasks.get(0).getObservations()).contains("REPROCESO");
    }

    @Test
    void cannotReceiveAlreadyReceivedUnit() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-005");
        createItem(po, 2, null);
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        Long unitId = workspace.getUnits().get(0).getId();

        WarehouseUnitReceiptRequest.UnitUpdate update = WarehouseUnitReceiptRequest.UnitUpdate.builder()
                .unitId(unitId)
                .receiptStatus("RECEIVED")
                .build();
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(List.of(update))
                .build());

        assertThatThrownBy(() -> warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(List.of(update))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No hubo cambios");
    }

    @Test
    void closeReceiptFailsWhenPendingUnitsRemain() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-006");
        createItem(po, 2, null);
        warehouseUnitService.getWorkspace(po.getId());

        assertThatThrownBy(() -> warehouseUnitService.closeWarehouseReceipt(po.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendientes");
    }

    @Test
    void closeReceiptSucceedsWhenAllAccounted() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-007");
        createItem(po, 2, null);
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());

        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build());

        var result = warehouseUnitService.closeWarehouseReceipt(po.getId());
        assertThat(result.get("warehouseReceiptClosedAt")).isNotNull();
        ProductionOrderEntity refreshed = productionOrderRepository.findById(po.getId()).orElseThrow();
        assertThat(refreshed.getWarehouseReceiptClosedAt()).isNotNull();
    }

    @Test
    void legacyUnitsWithoutSizeKeyCountForSingleSizeShipmentLine() throws Exception {
        ProductionOrderEntity po = createOrder("OP-WH-009");
        Map<String, Integer> sizes = Map.of("32", 4);
        ProductionOrderItemEntity item = createItem(po, 4, ProductInventorySizesJson.serialize(
                sizes.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> java.math.BigDecimal.valueOf(e.getValue())))));

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        for (ProductionOrderWarehouseUnitEntity unit : unitRepository.findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId())) {
            unit.setSizeKey("");
            unitRepository.save(unit);
        }
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build());

        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .productionOrderId(po.getId())
                .shipmentNumber("OP-WH-009-ENV-00001")
                .status("DRAFT")
                .build());
        shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .sizeLabel("32")
                .quantity(java.math.BigDecimal.valueOf(4))
                .build());

        warehouseUnitService.validateUnitsReadyForProductShipment(po.getId(), shipment.getId());
    }

    @Test
    void dispatchOnlineSale_withoutPtReceipt_succeeds() throws Exception {
        OnlineSaleEntity sale = onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber("V-TEST-NO-PT")
                .customerName("Cliente Sin PT")
                .status("PRODUCIDO")
                .saleDate(LocalDate.now())
                .inProductionOrder(true)
                .build());

        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-WH-009")
                .orderType("VENTA_EN_LINEA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());

        productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .onlineSaleId(sale.getId())
                .quantity(2)
                .warehouseReceivedQty(0)
                .build());

        warehouseUnitService.getWorkspace(po.getId());

        Map<String, Object> result = customerShipmentDispatchService.dispatchCustomerShipment(
                po.getId(), sale.getId(), Map.of());

        assertThat(result.get("saleStatus")).isEqualTo("ENVIADO");
        OnlineSaleEntity updated = onlineSaleRepository.findById(sale.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ENVIADO");
        assertThat(kardexRepository.findByReferenceTypeAndReferenceId(
                ProductInventoryService.MOVEMENT_ONLINE_SALE_DISPATCH, sale.getId())).isEmpty();
    }

    @Test
    void dispatchOplReceivedUnits_decrementsPtByExactQty() throws Exception {
        OnlineSaleEntity sale = onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber("V-TEST-PT-OUT")
                .customerName("Cliente PT Out")
                .status("PRODUCIDO")
                .saleDate(LocalDate.now())
                .inProductionOrder(true)
                .build());

        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-WH-010")
                .orderType("VENTA_EN_LINEA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());

        ProductionOrderItemEntity item = productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .onlineSaleId(sale.getId())
                .quantity(2)
                .warehouseReceivedQty(0)
                .build());

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build());

        assertThat(stockAtPt()).isEqualByComparingTo("2");

        Map<String, Object> result = customerShipmentDispatchService.dispatchCustomerShipment(
                po.getId(), sale.getId(), Map.of());

        assertThat(result.get("saleStatus")).isEqualTo("ENVIADO");
        assertThat(stockAtPt()).isEqualByComparingTo("0");

        List<ProductInventoryKardex> outflows = kardexRepository
                .findByReferenceTypeAndReferenceId(ProductInventoryService.MOVEMENT_ONLINE_SALE_DISPATCH, sale.getId())
                .stream()
                .filter(k -> k.getQuantity() != null && k.getQuantity().signum() < 0)
                .toList();
        assertThat(outflows).hasSize(2);
        assertThat(outflows).allMatch(k -> k.getReferenceLineId() != null);

        List<ProductionOrderWarehouseUnitEntity> units = unitRepository.findByProductionOrderIdAndProductionOrderItemIdIn(
                po.getId(), List.of(item.getId()));
        assertThat(units).allMatch(u -> u.getShippedAt() != null);
    }

    @Test
    void redispatchOpl_doesNotDoubleDeductPt() throws Exception {
        OnlineSaleEntity sale = onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber("V-TEST-REDISP")
                .customerName("Cliente Redispatch")
                .status("PRODUCIDO")
                .saleDate(LocalDate.now())
                .inProductionOrder(true)
                .build());

        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-WH-011")
                .orderType("VENTA_EN_LINEA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());

        productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .onlineSaleId(sale.getId())
                .quantity(2)
                .warehouseReceivedQty(0)
                .build());

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build());

        customerShipmentDispatchService.dispatchCustomerShipment(po.getId(), sale.getId(), Map.of());
        assertThat(stockAtPt()).isEqualByComparingTo("0");

        OnlineSaleEntity afterFirst = onlineSaleRepository.findById(sale.getId()).orElseThrow();
        afterFirst.setStatus("PRODUCIDO");
        onlineSaleRepository.save(afterFirst);

        customerShipmentDispatchService.dispatchCustomerShipment(po.getId(), sale.getId(), Map.of());
        assertThat(stockAtPt()).isEqualByComparingTo("0");

        long outflowCount = kardexRepository
                .findByReferenceTypeAndReferenceId(ProductInventoryService.MOVEMENT_ONLINE_SALE_DISPATCH, sale.getId())
                .stream()
                .filter(k -> k.getQuantity() != null && k.getQuantity().signum() < 0)
                .count();
        assertThat(outflowCount).isEqualTo(2);
    }

    @Test
    void dispatchMarksUnitsShippedForOnlineSale() throws Exception {
        OnlineSaleEntity sale = onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber("V-TEST-001")
                .customerName("Cliente Test")
                .status("PRODUCIDO")
                .saleDate(LocalDate.now())
                .build());

        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-WH-008")
                .orderType("VENTA_EN_LINEA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());

        ProductionOrderItemEntity item = productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .onlineSaleId(sale.getId())
                .quantity(2)
                .warehouseReceivedQty(0)
                .build());

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .collect(Collectors.toList()))
                .build());

        int shipped = warehouseUnitService.markUnitsShippedForOnlineSale(po.getId(), sale.getId(), userId);
        assertThat(shipped).isEqualTo(2);

        List<ProductionOrderWarehouseUnitEntity> units = unitRepository.findByProductionOrderIdAndProductionOrderItemIdIn(
                po.getId(), List.of(item.getId()));
        assertThat(units).allMatch(u -> u.getShippedAt() != null);
    }

    private BigDecimal stockAtPt() {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), bodegaPt.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }

    private ProductionOrderEntity createOrder(String code) {
        return productionOrderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType("INTERNA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity createItem(ProductionOrderEntity po, int qty, String sizesData) {
        return productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(qty)
                .sizesData(sizesData)
                .warehouseReceivedQty(0)
                .build());
    }
}
