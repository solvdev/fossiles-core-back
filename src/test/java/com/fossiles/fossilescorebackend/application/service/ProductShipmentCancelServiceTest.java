package com.fossiles.fossilescorebackend.application.service;

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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductShipmentCancelServiceTest {

    @Autowired
    private ProductDistributionService productDistributionService;

    @Autowired
    private ProductionOrderWarehouseUnitService warehouseUnitService;

    @Autowired
    private ProductionOrderRepository productionOrderRepository;

    @Autowired
    private ProductionOrderItemRepository productionOrderItemRepository;

    @Autowired
    private ProductionOrderWarehouseUnitRepository unitRepository;

    @Autowired
    private ProductShipmentRepository shipmentRepository;

    @Autowired
    private ProductShipmentDetailRepository shipmentDetailRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private InventoryLocationTypeRepository inventoryLocationTypeRepository;

    @Autowired
    private LocationRepository locationRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private final Long userId = 42L;
    private ProductEntity product;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega PT")
                .isActive(true)
                .build());
        locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega Producto Terminado")
                .categoria("BODEGA_PT")
                .build());
        product = productRepository.save(ProductEntity.builder()
                .code("CANCEL-PROD")
                .name("Producto cancel test")
                .requiresMaterials(false)
                .build());
        color = colorRepository.save(ColorEntity.builder().name("AZUL").build());
    }

    @Test
    void cancelDraft_setsCancelled() throws Exception {
        ProductShipmentEntity shipment = saveShipment("SHP-DRAFT-1", "DRAFT", null);
        var response = productDistributionService.cancelShipment(shipment.getId());
        assertThat(response.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelConfirmed_clearsWarehouseUnits() throws Exception {
        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-CANCEL-01")
                .orderType("INTERNA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());
        productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(2)
                .warehouseReceivedQty(0)
                .build());
        var workspace = warehouseUnitService.getWorkspace(po.getId());
        warehouseUnitService.updateUnitsReceipt(po.getId(),
                com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest.builder()
                        .units(workspace.getUnits().stream()
                                .map(u -> com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                        .unitId(u.getId())
                                        .receiptStatus("RECEIVED")
                                        .build())
                                .toList())
                        .build());

        ProductShipmentEntity shipment = saveShipment("SHP-CONF-1", "CONFIRMED", po.getId());
        warehouseUnitService.markUnitsShippedForProductShipment(po.getId(), shipment.getId(), userId);

        productDistributionService.cancelShipment(shipment.getId());

        var units = unitRepository.findByProductionOrderIdAndShipmentRefTypeAndShipmentRefId(
                po.getId(), ProductionOrderWarehouseUnitService.REF_PRODUCT_SHIPMENT, shipment.getId());
        assertThat(units).isEmpty();
        assertThat(shipmentRepository.findById(shipment.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelSent_throws() {
        ProductShipmentEntity shipment = saveShipment("SHP-SENT-1", "SENT", null);
        shipment.setSentAt(LocalDateTime.now());
        shipmentRepository.save(shipment);
        assertThatThrownBy(() -> productDistributionService.cancelShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("antes de enviar");
    }

    @Test
    void voidVendorShipmentDocument_interna_setsFlag() throws Exception {
        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-VOID-01")
                .orderType("INTERNA")
                .status("IN_PROGRESS")
                .sellerName("Interno")
                .vendorShipmentNumber("ENVI-00001")
                .startDate(LocalDate.now())
                .build());
        ProductionOrderEntity updated = productDistributionService.voidVendorShipmentDocument(po.getId());
        assertThat(updated.getVendorShipmentVoidedAt()).isNotNull();
        assertThat(updated.getVendorShipmentVoidedBy()).isEqualTo(userId);
    }

    @Test
    void voidVendorShipmentDocument_blocksWhenActiveShipment() {
        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OP-VOID-02")
                .orderType("INTERNA")
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());
        saveShipment("SHP-ACTIVE-1", "CONFIRMED", po.getId());
        assertThatThrownBy(() -> productDistributionService.voidVendorShipmentDocument(po.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("envío activo");
    }

    private ProductShipmentEntity saveShipment(String number, String status, Long productionOrderId) {
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber(number)
                .status(status)
                .productionOrderId(productionOrderId)
                .build());
        shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(BigDecimal.ONE)
                .build());
        return shipment;
    }
}
