package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.DispatchStockShortageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Flujo de autorización de envíos internos según stock:
 * sin stock se autoriza la producción (OPI por el faltante); con stock se autoriza el envío.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalShipmentRequestStockFlowTest {

    private static final long REQUEST_ID = 10L;
    private static final long PRODUCT_ID = 5L;
    private static final long OPI_ID = 77L;

    @Mock private InternalShipmentRequestRepository requestRepository;
    @Mock private ProductShipmentRepository shipmentRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProductDistributionService productDistributionService;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductionOrderCodeService productionOrderCodeService;
    @Mock private OpiVendorShipmentNumberService opiVendorShipmentNumberService;
    @Mock private InternalShipmentRequestSlipService slipService;
    @Mock private SmartMaterialRequestService smartMaterialRequestService;
    @Mock private ProductionAutoPlannerService productionAutoPlannerService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private SecurityUtil securityUtil;
    @Mock private InternalShipmentRequestAccessGuard accessGuard;

    @InjectMocks
    private InternalShipmentRequestService service;

    private InternalShipmentRequestEntity request;

    @BeforeEach
    void setUp() throws Exception {
        request = InternalShipmentRequestEntity.builder()
                .id(REQUEST_ID)
                .status("PENDIENTE")
                .requestType("PLANILLA")
                .recipientName("Colaborador Prueba")
                .lines(new ArrayList<>())
                .build();
        request.getLines().add(InternalShipmentRequestLineEntity.builder()
                .request(request)
                .lineOrder(1)
                .productId(PRODUCT_ID)
                .size("M")
                .quantity(BigDecimal.valueOf(10))
                .build());
        when(requestRepository.findByIdWithLines(REQUEST_ID)).thenReturn(Optional.of(request));
        when(accessGuard.canViewAccountingWorkspace()).thenReturn(true);

        when(productionOrderCodeService.generateNextCode("INTERNA")).thenReturn("OPI-TEST-1");
        when(productionOrderRepository.save(any(ProductionOrderEntity.class))).thenAnswer(inv -> {
            ProductionOrderEntity order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(OPI_ID);
            }
            return order;
        });
    }

    private static DispatchStockShortageResponse stockRow(int available, int required) {
        int shortage = Math.max(0, required - available);
        return DispatchStockShortageResponse.builder()
                .productId(PRODUCT_ID)
                .size("M")
                .availableQuantity(BigDecimal.valueOf(available))
                .requiredQuantity(BigDecimal.valueOf(required))
                .shortageQuantity(BigDecimal.valueOf(shortage))
                .build();
    }

    private void stockIs(int available) throws Exception {
        DispatchStockShortageResponse row = stockRow(available, 10);
        when(productDistributionService.computeDispatchStockLines(any())).thenReturn(List.of(row));
        when(productDistributionService.computeDispatchStockShortages(any()))
                .thenReturn(row.getShortageQuantity().signum() > 0 ? List.of(row) : List.of());
    }

    @Test
    void sinStock_sinOpi_indicaAutorizarProduccion() throws Exception {
        stockIs(4);

        InternalShipmentRequestResponse response = service.getById(REQUEST_ID);

        assertThat(response.getStockStatus()).isEqualTo("SIN_STOCK");
        assertThat(response.getNextAction()).isEqualTo("AUTORIZAR_PRODUCCION");
        assertThat(response.getStockShortageTotal()).isEqualByComparingTo("6");
        assertThat(response.getLines().get(0).getAvailableQuantity()).isEqualByComparingTo("4");
        assertThat(response.getLines().get(0).getShortageQuantity()).isEqualByComparingTo("6");
        assertThat(response.getProductionOrderId()).isNull();
    }

    @Test
    void conStock_indicaAutorizarEnvio() throws Exception {
        stockIs(25);

        InternalShipmentRequestResponse response = service.getById(REQUEST_ID);

        assertThat(response.getStockStatus()).isEqualTo("DISPONIBLE");
        assertThat(response.getNextAction()).isEqualTo("AUTORIZAR_ENVIO");
        assertThat(response.getLines().get(0).getShortageQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void faltanteConOpiAutorizada_indicaEsperarProduccion() throws Exception {
        stockIs(4);
        request.setProductionOrderId(OPI_ID);
        request.setOpiAuthorizedAt(java.time.LocalDateTime.now());
        ProductionOrderEntity opi = ProductionOrderEntity.builder().id(OPI_ID).code("OPI-TEST-1").status("PENDING").build();
        when(productionOrderRepository.findById(OPI_ID)).thenReturn(Optional.of(opi));

        InternalShipmentRequestResponse response = service.getById(REQUEST_ID);

        assertThat(response.getStockStatus()).isEqualTo("EN_PRODUCCION");
        assertThat(response.getNextAction()).isEqualTo("ESPERAR_PRODUCCION");
    }

    @Test
    void autorizarEnvioSinStock_noCreaOpiYPideAutorizarProduccion() throws Exception {
        stockIs(4);

        assertThatThrownBy(() -> service.approve(REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Autorice primero la producción");

        verify(productionOrderRepository, never()).save(any(ProductionOrderEntity.class));
        verify(productDistributionService, never()).dispatchStandaloneInternal(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(request.getProductionOrderId()).isNull();
        assertThat(request.getStatus()).isEqualTo("PENDIENTE");
    }

    @Test
    void autorizarProduccionSinStock_generaOpiPorFaltanteYLaAutoriza() throws Exception {
        stockIs(4);
        when(productionOrderRepository.findById(OPI_ID)).thenAnswer(inv -> {
            ProductionOrderEntity saved = ProductionOrderEntity.builder()
                    .id(OPI_ID).code("OPI-TEST-1").status("DRAFT").build();
            return Optional.of(saved);
        });

        InternalShipmentRequestResponse response = service.authorizeProduction(REQUEST_ID);

        assertThat(request.getProductionOrderId()).isEqualTo(OPI_ID);
        assertThat(request.getOpiAuthorizedAt()).isNotNull();
        assertThat(request.getStatus()).isEqualTo("PENDIENTE");
        assertThat(response.getProductionOrderId()).isEqualTo(OPI_ID);
        verify(productionOrderItemRepository).save(org.mockito.ArgumentMatchers.argThat(
                item -> item.getQuantity() == 6 && item.getProductId().equals(PRODUCT_ID)));
    }

    @Test
    void autorizarProduccionConStock_noGeneraOpi() throws Exception {
        stockIs(25);

        assertThatThrownBy(() -> service.authorizeProduction(REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("stock suficiente");

        verify(productionOrderRepository, never()).save(any(ProductionOrderEntity.class));
    }
}
