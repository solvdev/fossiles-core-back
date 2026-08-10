package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DocumentSeriesRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductDistributionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderPartialReleaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductDistributionServiceReceiptIdempotencyTest {

    @Mock private ProductDistributionRepository distributionRepository;
    @Mock private ProductShipmentRepository shipmentRepository;
    @Mock private ProductShipmentDetailRepository shipmentDetailRepository;
    @Mock private ProductRepository productRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private ProductInventoryLocationRepository productInventoryLocationRepository;
    @Mock private ProductInventoryKardexRepository productInventoryKardexRepository;
    @Mock private InventoryLocationTypeRepository inventoryLocationTypeRepository;
    @Mock private ProductInventoryService productInventoryService;
    @Mock private KioscoInventoryService kioscoInventoryService;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private DocumentSeriesRepository documentSeriesRepository;
    @Mock private OpiVendorShipmentNumberService opiVendorShipmentNumberService;
    @Mock private OpvVendorShipmentNumberService opvVendorShipmentNumberService;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private ProductCategoryRepository productCategoryRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private KioscoStockRepository kioscoStockRepository;
    @Mock private KioscoMovementRepository kioscoMovementRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private ProductionOrderWarehouseUnitService productionOrderWarehouseUnitService;
    @Mock private ProductionOrderPartialReleaseRepository partialReleaseRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ProductDistributionService service;

    private final Long locationId = 10L;
    private final Long shipmentId = 100L;
    private final Long productId = 30L;
    private final Long colorId = 40L;
    private final Long detailId = 1L;
    private final Long userId = 50L;

    private ProductShipmentEntity shipment;
    private ProductShipmentDetailEntity detail;
    private String lineRef;

    @BeforeEach
    void setUp() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        shipment = ProductShipmentEntity.builder()
                .id(shipmentId)
                .shipmentNumber("ENV-1")
                .locationId(locationId)
                .status("DELIVERED")
                .build();
        detail = ProductShipmentDetailEntity.builder()
                .id(detailId)
                .shipmentId(shipmentId)
                .productId(productId)
                .colorId(colorId)
                .quantity(new BigDecimal("5"))
                .quantityReceived(new BigDecimal("5"))
                .sizeLabel("")
                .build();
        lineRef = "ENV-1#L" + detailId;
        when(productInventoryService.getInventoryByProductAndLocationAndColor(productId, locationId, colorId))
                .thenReturn(ProductInventoryLocationResponse.builder()
                        .productId(productId)
                        .locationId(locationId)
                        .colorId(colorId)
                        .quantity(new BigDecimal("5"))
                        .build());
    }

    @Test
    void applyReceipt_normalOnce_appliesKioscoAndKardex() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(false);
        when(productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipmentId, "TRANSFER_IN", productId, locationId, colorId, lineRef))
                .thenReturn(false);

        invokeApplyReceipt(new BigDecimal("5"));

        verify(kioscoInventoryService).registrarEntradaDesdeIntegracion(
                eq(locationId), eq(productId), eq(colorId), eq(new BigDecimal("5")),
                eq(shipmentId), eq(userId), isNull(), eq(lineRef), isNull());
        verify(productInventoryService).incrementInventory(
                eq(productId), eq(locationId), eq(colorId), eq(new BigDecimal("5")),
                isNull(), eq("SHIPMENT"), eq(shipmentId), eq("ENV-1"), any(), isNull());
        verify(productInventoryService).recordProductMovementIfAbsent(
                eq(productId), eq(locationId), eq(colorId), eq("TRANSFER_IN"),
                eq(new BigDecimal("5")), any(), any(), isNull(),
                eq("SHIPMENT"), eq(shipmentId), eq(lineRef), any());
    }

    @Test
    void applyReceipt_retryBothApplied_isNoOp() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(true);
        when(productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipmentId, "TRANSFER_IN", productId, locationId, colorId, lineRef))
                .thenReturn(true);

        invokeApplyReceipt(new BigDecimal("5"));

        verify(kioscoInventoryService, never()).registrarEntradaDesdeIntegracion(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(productInventoryService, never()).incrementInventory(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(productInventoryService, never()).recordProductMovementIfAbsent(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void applyReceipt_kioscoOkKardexMissing_onlyBackfillsKardex() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(true);
        when(productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipmentId, "TRANSFER_IN", productId, locationId, colorId, lineRef))
                .thenReturn(false);

        invokeApplyReceipt(new BigDecimal("5"));

        verify(kioscoInventoryService, never()).registrarEntradaDesdeIntegracion(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(productInventoryService, never()).incrementInventory(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(productInventoryService).recordProductMovementIfAbsent(
                eq(productId), eq(locationId), eq(colorId), eq("TRANSFER_IN"),
                eq(new BigDecimal("5")), any(), any(), isNull(),
                eq("SHIPMENT"), eq(shipmentId), eq(lineRef), any());
    }

    @Test
    void applyReceipt_kardexOkKioscoMissing_onlyAppliesKiosco() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(false);
        when(productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipmentId, "TRANSFER_IN", productId, locationId, colorId, lineRef))
                .thenReturn(true);

        invokeApplyReceipt(new BigDecimal("5"));

        verify(kioscoInventoryService).registrarEntradaDesdeIntegracion(
                eq(locationId), eq(productId), eq(colorId), eq(new BigDecimal("5")),
                eq(shipmentId), eq(userId), isNull(), eq(lineRef), isNull());
        verify(productInventoryService, never()).incrementInventory(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(productInventoryService, never()).recordProductMovementIfAbsent(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void applyKioscoReceiptLineOnly_skipsWhenAlreadyApplied() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                service, "applyKioscoReceiptLineOnly", shipment, detail, new BigDecimal("5"), lineRef);

        verify(kioscoInventoryService, never()).registrarEntradaDesdeIntegracion(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void forceRepair_doesNotCreateSecondEntradaWithSameLineRef() throws Exception {
        when(kioscoInventoryService.hasShipmentReceiptLineApplied(locationId, shipmentId, lineRef)).thenReturn(true);
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId))
                .thenReturn(java.util.Optional.of(
                        com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                                .id(1L)
                                .locationId(locationId)
                                .productId(productId)
                                .colorId(colorId)
                                .currentStock(2)
                                .build()));

        Boolean repaired = ReflectionTestUtils.invokeMethod(
                service, "repairShipmentProductLineIfMissing", shipment, detail, new BigDecimal("5"), true);

        assertThat(repaired).isTrue();
        verify(kioscoInventoryService, never()).registrarEntradaDesdeIntegracion(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(kioscoInventoryService).registrarAjuste(
                eq(locationId),
                eq(productId),
                eq(colorId),
                eq(5),
                isNull(),
                eq("Reparación recepción envío · SHIPMENT_RCPT:ENV-1#L1#REPAIR"),
                eq(userId),
                isNull());
    }

    @Test
    void reasonContainsShipmentReceiptLine_distinguishesL1FromL10() {
        String reasonL10 = "Recepción envío ENV-1 · SHIPMENT_RCPT:ENV-1#L10";
        String reasonL1 = "Recepción envío ENV-1 · SHIPMENT_RCPT:ENV-1#L1";

        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL10, "ENV-1#L1")).isFalse();
        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL10, "ENV-1#L10")).isTrue();
        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL1, "ENV-1#L1")).isTrue();
        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL1, "ENV-1#L10")).isFalse();
    }

    private void invokeApplyReceipt(BigDecimal qty) throws Exception {
        ReflectionTestUtils.invokeMethod(
                service, "applyReceiptInventoryForDetail", shipment, detail, qty, lineRef);
    }
}
