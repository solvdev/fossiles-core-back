package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentDispatchStockCheckResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryService.DispatchSource;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DistributionOrigin;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductDistributionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Origen de descarga de los envíos: los directos a kiosco salen de Devoluciones y los de distribución de Bodega PT;
 * en ambos el faltante se toma de la otra cubeta solo con autorización explícita, y queda escrito en el kardex.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductDistributionServiceDispatchSourceTest {

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
    @Mock private ShipmentNumberCounterService shipmentNumberCounterService;
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
    @Mock private CustomerAccountService customerAccountService;
    @Mock private ObjectMapper objectMapper;
    @Mock private EmployeeChargeService employeeChargeService;

    @InjectMocks
    private ProductDistributionService service;

    private static final Long KIOSK_ID = 10L;
    private static final Long SHIPMENT_ID = 100L;
    private static final Long DETAIL_ID = 1L;
    private static final Long PRODUCT_ID = 30L;
    private static final Long COLOR_ID = 40L;
    private static final Long USER_ID = 50L;
    private static final Long DEV_ID = 41L;
    private static final Long PT_ID = 38L;

    private final LocationEntity devoluciones = LocationEntity.builder().id(DEV_ID).code("DEVOLUCIONES").name("Devoluciones").build();
    private final LocationEntity bodegaPt = LocationEntity.builder().id(PT_ID).code("BODEGA_PT").name("Bodega PT").build();

    @BeforeEach
    void setUp() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                UserEntity.builder().id(USER_ID).username("ana").firstName("Ana").lastName("QA").build()));
        when(locationRepository.findById(KIOSK_ID)).thenReturn(Optional.of(LocationEntity.builder()
                .id(KIOSK_ID).code("INT_ESCUINT").name("Kiosko Escuintla").categoria("KIOSKO").build()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(ProductEntity.builder()
                .id(PRODUCT_ID).code("B-56").name("Bolso B-56").build()));

        when(productInventoryService.getDispatchSourceWarehouses(DispatchSource.RETURNS_ONLY)).thenReturn(List.of(devoluciones));
        when(productInventoryService.getDispatchSourceWarehouses(DispatchSource.PT_ONLY)).thenReturn(List.of(bodegaPt));
        when(productInventoryService.getDispatchSourceWarehouses(DispatchSource.RETURNS_FIRST)).thenReturn(List.of(devoluciones, bodegaPt));
        when(productInventoryService.getDispatchSourceWarehouses(DispatchSource.PT_FIRST)).thenReturn(List.of(bodegaPt, devoluciones));
        when(productInventoryService.getNetConsumedForLine(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        when(shipmentRepository.markSentIfConfirmed(eq(SHIPMENT_ID), any(), any())).thenReturn(1);
        when(shipmentDetailRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(ProductShipmentDetailEntity.builder()
                .id(DETAIL_ID)
                .shipmentId(SHIPMENT_ID)
                .productId(PRODUCT_ID)
                .colorId(COLOR_ID)
                .sizeLabel("")
                .quantity(new BigDecimal("3"))
                .build()));
    }

    // ---------- (a) matriz de resolveDispatchSource ----------

    @Test
    void shipmentInADistributionLeavesFromBodegaPtOnly() {
        for (String origin : List.of(DistributionOrigin.MANUAL, DistributionOrigin.REPOSICION)) {
            ProductShipmentEntity s = kioskShipment(7L, null);
            when(distributionRepository.findById(7L)).thenReturn(Optional.of(ProductDistributionEntity.builder()
                    .id(7L).origin(origin).description("Distribución semanal").build()));

            ProductDistributionService.DispatchPlan plan = service.resolveDispatchPlan(s);

            assertThat(plan.primary()).isEqualTo(DispatchSource.PT_ONLY);
            assertThat(plan.withFallback()).isEqualTo(DispatchSource.PT_FIRST);
            assertThat(plan.fallbackLabel()).isEqualTo("Devoluciones");
            assertThat(plan.directKiosk()).isFalse();
        }
    }

    @Test
    void opkShipmentInsideItsDistributionLeavesFromBodegaPt() {
        ProductShipmentEntity s = kioskShipment(7L, 900L);
        when(distributionRepository.findById(7L)).thenReturn(Optional.of(ProductDistributionEntity.builder()
                .id(7L).origin(DistributionOrigin.MANUAL).description("Envíos de OPK-12").build()));
        when(productionOrderRepository.findById(900L)).thenReturn(Optional.of(order("NORMAL")));

        assertThat(service.resolveDispatchSource(s)).isEqualTo(DispatchSource.PT_ONLY);
    }

    @Test
    void directKioskShipmentLeavesFromReturnsOnlyWithPtAsAuthorizedFallback() {
        ProductDistributionService.DispatchPlan plan = service.resolveDispatchPlan(kioskShipment(null, null));

        assertThat(plan.primary()).isEqualTo(DispatchSource.RETURNS_ONLY);
        assertThat(plan.withFallback()).isEqualTo(DispatchSource.RETURNS_FIRST);
        assertThat(plan.fallbackLabel()).isEqualTo("Bodega PT");
        assertThat(plan.directKiosk()).isTrue();
    }

    @Test
    void legacyDirectKioskDistributionStillCountsAsDirect() {
        ProductShipmentEntity s = kioskShipment(8L, null);
        ProductDistributionEntity legacy = ProductDistributionEntity.builder().id(8L).origin(DistributionOrigin.MANUAL)
                .description(ProductDistributionService.DIRECT_KIOSK_DISTRIBUTION_DESCRIPTION + " · Kiosko Escuintla")
                .build();
        when(distributionRepository.findById(8L)).thenReturn(Optional.of(legacy));

        assertThat(service.resolveDispatchSource(s)).isEqualTo(DispatchSource.RETURNS_ONLY);
        assertThat(ProductDistributionService.isDirectKioskShipment(s, legacy)).isTrue();
    }

    @Test
    void madeToOrderShipmentsStayPtFirst() {
        for (String type : List.of("INTERNA", "CLIENTE_KIOSKO", "CINCHOS_FOSSILES")) {
            when(productionOrderRepository.findById(900L)).thenReturn(Optional.of(order(type)));
            ProductDistributionService.DispatchPlan plan = service.resolveDispatchPlan(kioskShipment(null, 900L));
            assertThat(plan.primary()).as(type).isEqualTo(DispatchSource.PT_FIRST);
            assertThat(plan.hasFallback()).as(type).isFalse();
        }
    }

    @Test
    void internalEnviStaysReturnsOnlyWithoutFallback() {
        ProductShipmentEntity envi = ProductShipmentEntity.builder()
                .id(SHIPMENT_ID).shipmentNumber("ENVI-00012").status("CONFIRMED").build();

        ProductDistributionService.DispatchPlan plan = service.resolveDispatchPlan(envi);

        assertThat(plan.primary()).isEqualTo(DispatchSource.RETURNS_ONLY);
        assertThat(plan.hasFallback()).isFalse();
        assertThat(ProductDistributionService.isDirectKioskShipment(envi, null)).isFalse();
    }

    // ---------- (c) (d) (e) envío directo ----------

    @Test
    void directShipmentWithShortInReturnsFailsWithBreakdownAndDoesNotTouchStock() throws Exception {
        givenShipment(kioskShipment(null, null));
        givenStock("1", "5");

        assertThatThrownBy(() -> service.sendShipment(SHIPMENT_ID, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente en Devoluciones para el envío directo a kiosco")
                .hasMessageContaining("Devoluciones 1")
                .hasMessageContaining("Bodega PT 5")
                .hasMessageContaining("requerido 3")
                .hasMessageContaining("Tomar el faltante de Bodega PT");

        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(shipmentRepository, never()).markSentIfConfirmed(anyLong(), any(), any());
    }

    @Test
    void directShipmentWithAuthorizationTakesReturnsThenBodegaPtAndWritesItInTheKardex() throws Exception {
        givenShipment(kioskShipment(null, null));
        givenStock("1", "5");

        service.sendShipment(SHIPMENT_ID, true);

        InOrder order = inOrder(productInventoryService);
        order.verify(productInventoryService).decrementFromDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), eq(""), eq(new BigDecimal("3")), eq("SHIPMENT"), eq(SHIPMENT_ID),
                eq("INT_ESCUINT-ENV-00065"),
                eq("Envío directo a kiosco · Salida a envio en transito hacia Kiosko Escuintla"),
                eq(ProductInventoryService.MOVEMENT_SHIPMENT), eq(DETAIL_ID), eq(false), eq(DispatchSource.RETURNS_ONLY));
        order.verify(productInventoryService).decrementFromDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), eq(""), eq(new BigDecimal("3")), eq("SHIPMENT"), eq(SHIPMENT_ID),
                eq("INT_ESCUINT-ENV-00065"),
                eq("Envío directo a kiosco · Salida a envio en transito hacia Kiosko Escuintla"
                        + " · faltante tomado de Bodega PT (autorizado por Ana QA)"),
                eq(ProductInventoryService.MOVEMENT_SHIPMENT), eq(DETAIL_ID), eq(true), eq(DispatchSource.RETURNS_FIRST));
        verify(shipmentRepository).markSentIfConfirmed(eq(SHIPMENT_ID), any(), eq(USER_ID));
    }

    @Test
    void directShipmentWithEnoughReturnsNeverTouchesBodegaPt() throws Exception {
        givenShipment(kioskShipment(null, null));
        givenStock("5", "5");

        service.sendShipment(SHIPMENT_ID, false);

        verify(productInventoryService, times(1)).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), startsWith("Envío directo a kiosco · "),
                any(), any(), eq(true), eq(DispatchSource.RETURNS_ONLY));
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                eq(DispatchSource.RETURNS_FIRST));
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), contains("faltante"), any(), any(), anyBoolean(), any());
    }

    // ---------- envío con distribución: PT; faltante de Devoluciones con autorización ----------

    @Test
    void distributionShipmentWithoutPtFailsAndOffersReturns() throws Exception {
        givenShipment(kioskShipment(7L, null));
        givenDistribution();
        givenStock("5", "0");

        assertThatThrownBy(() -> service.sendShipment(SHIPMENT_ID, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente en Bodega PT para enviar")
                .hasMessageContaining("Tomar el faltante de Devoluciones");
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void distributionShipmentWithAuthorizationTakesPtThenReturns() throws Exception {
        givenShipment(kioskShipment(7L, null));
        givenDistribution();
        givenStock("5", "1");

        service.sendShipment(SHIPMENT_ID, true);

        InOrder order = inOrder(productInventoryService);
        order.verify(productInventoryService).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(),
                eq("Salida a envio en transito hacia Kiosko Escuintla"),
                any(), any(), eq(false), eq(DispatchSource.PT_ONLY));
        order.verify(productInventoryService).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(),
                eq("Salida a envio en transito hacia Kiosko Escuintla"
                        + " · faltante tomado de Devoluciones (autorizado por Ana QA)"),
                any(), any(), eq(true), eq(DispatchSource.PT_FIRST));
    }

    @Test
    void distributionShipmentWithEnoughPtLeavesFromPtOnly() throws Exception {
        givenShipment(kioskShipment(7L, null));
        givenDistribution();
        givenStock("5", "3");

        service.sendShipment(SHIPMENT_ID, false);

        verify(productInventoryService).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(),
                eq("Salida a envio en transito hacia Kiosko Escuintla"),
                any(), any(), eq(true), eq(DispatchSource.PT_ONLY));
        verify(productInventoryService, times(1)).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    // ---------- cinchos: siempre de Bodega PT, salvo los de una OPL y los de un ENVI interno ----------

    @Test
    void cinchoInInternalEnviLeavesFromReturnsOnly() {
        ProductShipmentEntity envi = ProductShipmentEntity.builder()
                .id(SHIPMENT_ID).shipmentNumber("ENVI-00012").status("CONFIRMED").build();
        givenCinchoLine();

        ProductDistributionService.DispatchPlan plan =
                service.resolveLineDispatchPlan(envi, service.resolveDispatchPlan(envi), PRODUCT_ID);

        assertThat(plan.primary()).isEqualTo(DispatchSource.RETURNS_ONLY);
        assertThat(plan.hasFallback()).isFalse();
    }

    @Test
    void cinchoInDirectShipmentLeavesFromBodegaPtEvenWithReturnsStock() throws Exception {
        givenShipment(kioskShipment(null, null));
        givenCinchoLine();
        givenStock("5", "5");

        service.sendShipment(SHIPMENT_ID, false);

        verify(productInventoryService).decrementFromDispatchWarehouses(
                any(), any(), eq("34"), any(), any(), any(), any(),
                eq("Envío directo a kiosco · Salida a envio en transito hacia Kiosko Escuintla"),
                any(), any(), eq(true), eq(DispatchSource.PT_ONLY));
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                eq(DispatchSource.RETURNS_ONLY));
    }

    @Test
    void cinchoWithoutBodegaPtStockFailsAndIsNeverTakenFromReturns() throws Exception {
        givenShipment(kioskShipment(7L, null));
        givenDistribution();
        givenCinchoLine();
        givenStock("5", "1");

        assertThatThrownBy(() -> service.sendShipment(SHIPMENT_ID, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disponible 1 (Bodega PT")
                .hasMessageContaining("Los cinchos salen solo de Bodega PT");
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void cinchoOfAnOnlineSaleOrderLeavesFromReturns() throws Exception {
        givenShipment(kioskShipment(null, 900L));
        when(productionOrderRepository.findById(900L)).thenReturn(Optional.of(
                ProductionOrderEntity.builder().id(900L).code("OPL-1201").orderType("VENTA_EN_LINEA").build()));
        givenCinchoLine();
        givenStock("5", "5");

        service.sendShipment(SHIPMENT_ID, false);

        verify(productInventoryService).decrementFromDispatchWarehouses(
                any(), any(), eq("34"), any(), any(), any(), any(), any(), any(), any(), eq(true),
                eq(DispatchSource.RETURNS_FIRST));
        verify(productInventoryService, never()).decrementFromDispatchWarehouses(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                eq(DispatchSource.PT_ONLY));
    }

    @Test
    void stockCheckShowsCinchoAgainstBodegaPtOnly() throws Exception {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(kioskShipment(null, null)));
        givenCinchoLine();
        givenStock("5", "1");

        ShipmentDispatchStockCheckResponse check = service.checkShipmentDispatchStock(SHIPMENT_ID);

        ShipmentDispatchStockCheckResponse.Line line = check.getLines().get(0);
        assertThat(line.getPrimaryAvailable()).isEqualByComparingTo("1");
        assertThat(line.getShortageQuantity()).isEqualByComparingTo("2");
        // Sin respaldo: Devoluciones no cubre el faltante de un cincho.
        assertThat(line.getShortageAfterFallback()).isEqualByComparingTo("2");
        assertThat(check.getCanSendWithFallback()).isFalse();
    }

    // ---------- desglose antes de enviar ----------

    @Test
    void stockCheckShowsBothWarehousesAndWhetherTheFallbackCoversTheShortage() throws Exception {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(kioskShipment(null, null)));
        givenStock("1", "5");

        ShipmentDispatchStockCheckResponse check = service.checkShipmentDispatchStock(SHIPMENT_ID);

        assertThat(check.getDirectKioskShipment()).isTrue();
        assertThat(check.getPrimaryWarehouseLabel()).isEqualTo("Devoluciones");
        assertThat(check.getFallbackWarehouseLabel()).isEqualTo("Bodega PT");
        assertThat(check.getHasShortage()).isTrue();
        assertThat(check.getCanSendWithFallback()).isTrue();
        ShipmentDispatchStockCheckResponse.Line line = check.getLines().get(0);
        assertThat(line.getReturnsAvailable()).isEqualByComparingTo("1");
        assertThat(line.getPtAvailable()).isEqualByComparingTo("5");
        assertThat(line.getShortageQuantity()).isEqualByComparingTo("2");
        assertThat(line.getShortageAfterFallback()).isEqualByComparingTo("0");
    }

    private ProductShipmentEntity kioskShipment(Long distributionId, Long productionOrderId) {
        return ProductShipmentEntity.builder()
                .id(SHIPMENT_ID)
                .shipmentNumber("INT_ESCUINT-ENV-00065")
                .distributionId(distributionId)
                .productionOrderId(productionOrderId)
                .withoutDistribution(distributionId == null && productionOrderId == null)
                .locationId(KIOSK_ID)
                .status("CONFIRMED")
                .build();
    }

    private ProductionOrderEntity order(String type) {
        return ProductionOrderEntity.builder().id(900L).code("OP-900").orderType(type).build();
    }

    private void givenShipment(ProductShipmentEntity shipment) {
        when(shipmentRepository.findByIdForUpdate(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
    }

    private void givenDistribution() {
        when(distributionRepository.findById(7L)).thenReturn(Optional.of(ProductDistributionEntity.builder()
                .id(7L).distributionNumber("DIST-20261021-060").origin(DistributionOrigin.MANUAL)
                .description("Distribución semanal").build()));
    }

    /** La línea del envío pasa a ser un cincho FOSS talla 34. */
    private void givenCinchoLine() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(ProductEntity.builder()
                .id(PRODUCT_ID).code("FOSS-4").name("CINCHO BERNARDO").cinchoType("FOSS").build()));
        when(shipmentDetailRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(List.of(ProductShipmentDetailEntity.builder()
                .id(DETAIL_ID)
                .shipmentId(SHIPMENT_ID)
                .productId(PRODUCT_ID)
                .colorId(COLOR_ID)
                .sizeLabel("34")
                .quantity(new BigDecimal("3"))
                .build()));
    }

    /** Disponible por cubeta para la línea del envío; los totales por fuente salen de las dos. */
    private void givenStock(String returnsQty, String ptQty) throws Exception {
        BigDecimal dev = new BigDecimal(returnsQty);
        BigDecimal pt = new BigDecimal(ptQty);
        when(productInventoryService.getAvailableQuantity(eq(PRODUCT_ID), eq(DEV_ID), eq(COLOR_ID), any())).thenReturn(dev);
        when(productInventoryService.getAvailableQuantity(eq(PRODUCT_ID), eq(PT_ID), eq(COLOR_ID), any())).thenReturn(pt);
        when(productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), any(), eq(DispatchSource.RETURNS_ONLY))).thenReturn(dev);
        when(productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), any(), eq(DispatchSource.PT_ONLY))).thenReturn(pt);
        when(productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), any(), eq(DispatchSource.RETURNS_FIRST))).thenReturn(dev.add(pt));
        when(productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                eq(PRODUCT_ID), eq(COLOR_ID), any(), eq(DispatchSource.PT_FIRST))).thenReturn(dev.add(pt));
    }
}
