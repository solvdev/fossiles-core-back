package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryTrasladoRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryTransferRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioscoInventoryServiceTest {

    @Mock
    private KioscoStockRepository kioscoStockRepository;
    @Mock
    private KioscoStockProvisioningService kioscoStockProvisioningService;
    @Mock
    private KioscoMovementRepository kioscoMovementRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ColorRepository colorRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private ProductInventoryService productInventoryService;
    @Mock
    private ProductInventoryLocationRepository productInventoryLocationRepository;
    @Mock
    private KioskInventoryGuard kioskInventoryGuard;
    @Mock
    private ProductShipmentRepository productShipmentRepository;
    @Mock
    private ProductInventoryKardexRepository productInventoryKardexRepository;
    @Mock
    private InventoryTransferRepository inventoryTransferRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Query nativeQuery;

    @InjectMocks
    private KioscoInventoryService service;

    private final Long locationId = 10L;
    private final Long locationDestId = 11L;
    private final Long productId = 30L;
    private final Long colorId = 40L;
    private final Long userId = 50L;

    @BeforeEach
    void setUp() throws Exception {
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(LocationEntity.builder()
                .id(locationId)
                .name("Kiosko A")
                .code("K-A")
                .categoria("KIOSKO")
                .build()));
        when(locationRepository.findById(locationDestId)).thenReturn(Optional.of(LocationEntity.builder()
                .id(locationDestId)
                .name("Kiosko B")
                .code("K-B")
                .categoria("KIOSKO")
                .build()));
        when(kioskInventoryGuard.isKioskLocation(any(LocationEntity.class))).thenReturn(true);
        when(productRepository.existsById(productId)).thenReturn(true);
        when(productRepository.findById(productId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(productId)
                .code("SUM-001")
                .name("Empaque")
                .build()));
        when(colorRepository.existsById(colorId)).thenReturn(true);
        when(productInventoryLocationRepository.findByProductIdAndLocationIdAndColorId(
                productId, locationId, colorId)).thenReturn(Optional.empty());
        when(userRepository.existsById(userId)).thenReturn(true);
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        when(kioscoStockRepository.save(any(KioscoStockEntity.class))).thenAnswer(inv -> {
            KioscoStockEntity stock = inv.getArgument(0);
            if (stock.getId() == null) {
                stock.setId(999L);
            }
            if (stock.getLastUpdatedAt() == null) {
                stock.setLastUpdatedAt(LocalDateTime.now());
            }
            return stock;
        });
        when(kioscoMovementRepository.save(any(KioscoMovementEntity.class))).thenAnswer(inv -> {
            KioscoMovementEntity movement = inv.getArgument(0);
            movement.setId(1000L);
            return movement;
        });
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn("true");
        when(nativeQuery.executeUpdate()).thenReturn(1);
        when(kioscoStockProvisioningService.ensureStockRow(anyLong(), anyLong(), any(), anyLong(), any()))
                .thenAnswer(inv -> {
                    Long loc = inv.getArgument(0);
                    Long prod = inv.getArgument(1);
                    Long color = inv.getArgument(2);
                    Optional<KioscoStockEntity> locked = kioscoStockRepository.findForUpdate(loc, prod, color);
                    if (locked.isPresent()) {
                        return locked.get();
                    }
                    Optional<KioscoStockEntity> byHw = kioscoStockRepository.findForUpdateByHardware(
                            loc, prod, color, inv.getArgument(4));
                    return byHw.orElseGet(() -> stockEntity(0, 0));
                });
    }

    @Test
    void entrada_registraCorrectamente_ySumaStock() throws Exception {
        KioscoStockEntity stock = stockEntity(5, 2);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        KioscoStockResponse response = service.registrarEntrada(locationId, productId, colorId, 3, 123L, userId);

        assertThat(response.getCurrentStock()).isEqualTo(8);
        verify(productInventoryService).incrementInventory(
                eq(productId), eq(locationId), eq(colorId), eq(new BigDecimal("3")),
                eq(null), eq("KIOSCO_INVENTORY"), eq(null), eq(null), any(), eq(null));
    }

    @Test
    void entradaDesdeIntegracion_conTalla_actualizaSizesData() throws Exception {
        KioscoStockEntity stock = stockEntity(0, 0);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        KioscoStockResponse response = service.registrarEntradaDesdeIntegracion(
                locationId, productId, colorId, new BigDecimal("4"), 200L, userId, "32");

        assertThat(response.getCurrentStock()).isEqualTo(4);
        // Sin sizes_data previo no se inventa desglose; la talla queda en el movimiento.
        ArgumentCaptor<KioscoMovementEntity> movementCaptor = ArgumentCaptor.forClass(KioscoMovementEntity.class);
        verify(kioscoMovementRepository).save(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getSizeKey()).isEqualTo("32");
        verify(productInventoryService, never()).incrementInventory(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void entradaDesdeIntegracion_noOp_siLineaRecepcionYaAplicada() throws Exception {
        Long shipmentId = 200L;
        String lineRef = "ENV-1#L1";
        when(productShipmentRepository.findById(shipmentId)).thenReturn(Optional.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity.builder()
                        .id(shipmentId)
                        .locationId(locationId)
                        .shipmentNumber("ENV-1")
                        .build()));
        when(kioscoMovementRepository.existsShipmentReceiptLine(eq(locationId), eq(shipmentId), any()))
                .thenReturn(true);
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                eq(locationId), eq(productId), eq(colorId), any()))
                .thenReturn(Optional.of(stockEntity(8, 0)));

        KioscoStockResponse response = service.registrarEntradaDesdeIntegracion(
                locationId, productId, colorId, new BigDecimal("4"), shipmentId, userId, null, lineRef);

        assertThat(response.getCurrentStock()).isEqualTo(8);
        verify(kioscoMovementRepository, never()).save(any());
        verify(kioscoStockRepository, never()).save(any());
    }

    @Test
    void hasShipmentReceiptLineApplied_distinguishesL1FromL10() {
        Long shipmentId = 200L;
        when(kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, "SHIPMENT_RCPT:ENV-1#L1"))
                .thenReturn(false);
        when(kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, "ENV-1#L1"))
                .thenReturn(false);
        when(kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, "SHIPMENT_RCPT:ENV-1#L10"))
                .thenReturn(true);

        assertThat(service.hasShipmentReceiptLineApplied(locationId, shipmentId, "ENV-1#L1")).isFalse();
        assertThat(service.hasShipmentReceiptLineApplied(locationId, shipmentId, "ENV-1#L10")).isTrue();
    }

    @Test
    void reasonContainsShipmentReceiptLine_distinguishesL1FromL10() {
        String reasonL10 = "Recepción envío ENV-1 · SHIPMENT_RCPT:ENV-1#L10";
        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL10, "ENV-1#L1")).isFalse();
        assertThat(KioscoInventoryService.reasonContainsShipmentReceiptLine(reasonL10, "ENV-1#L10")).isTrue();
    }

    @Test
    void entrada_falla_siLocationNoEsKiosko() {
        when(kioskInventoryGuard.isKioskLocation(any(LocationEntity.class))).thenReturn(false);

        assertThatThrownBy(() -> service.registrarEntrada(locationId, productId, colorId, 1, null, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no es de tipo kiosko");
    }

    @Test
    void entrada_falla_siProductoNoExiste() {
        when(productRepository.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> service.registrarEntrada(locationId, productId, colorId, 1, null, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void entrada_falla_siCantidadNoValida() {
        assertThatThrownBy(() -> service.registrarEntrada(locationId, productId, colorId, 0, null, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mayor a cero");
    }

    @Test
    void venta_registraCorrectamente_yRestaStock() throws Exception {
        KioscoStockEntity stock = stockEntity(10, 2);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(7, 2)));

        KioscoStockResponse response = service.registrarVenta(locationId, productId, colorId, 3, 777L, userId);

        assertThat(response.getCurrentStock()).isEqualTo(7);
        verify(productInventoryService).decrementInventory(
                eq(productId), eq(locationId), eq(colorId), eq(new BigDecimal("3")),
                eq("KIOSCO_INVENTORY"), eq(null), eq(null), any(), eq(null));
    }

    @Test
    void venta_emiteAlertaCuandoQuedaBajoMinimo() throws Exception {
        KioscoStockEntity stock = stockEntity(5, 2);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(2, 2)));

        service.registrarVenta(locationId, productId, colorId, 3, 1L, userId);

        verify(kioscoStockRepository).findByLocationIdAndProductIdAndColorId(locationId, productId, colorId);
    }

    @Test
    void venta_falla_siStockInsuficiente() {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(2, 1)));

        assertThatThrownBy(() -> service.registrarVenta(locationId, productId, colorId, 3, 1L, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void venta_falla_siCantidadNoValida() {
        assertThatThrownBy(() -> service.registrarVenta(locationId, productId, colorId, 0, 1L, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void devolucionDeposito_restaStockCorrectamente() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(6, 0)));

        KioscoStockResponse response = service.registrarDevolucionDeposito(locationId, productId, colorId, 2, 88L, userId);

        assertThat(response.getCurrentStock()).isEqualTo(4);
    }

    @Test
    void devolucionDeposito_falla_siStockInsuficiente() {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(1, 0)));

        assertThatThrownBy(() -> service.registrarDevolucionDeposito(locationId, productId, colorId, 2, 88L, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void devolucionCliente_apto_true_sumaStock() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(4, 0)));

        KioscoStockResponse response = service.registrarDevolucionCliente(locationId, productId, colorId, 2, 500L, true, userId);

        assertThat(response.getCurrentStock()).isEqualTo(6);
    }

    @Test
    void devolucionCliente_apto_false_noModificaStock_yRegistraMerma() throws Exception {
        KioscoStockEntity stock = stockEntity(4, 0);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        KioscoStockResponse response = service.registrarDevolucionCliente(locationId, productId, colorId, 2, 500L, false, userId);

        assertThat(response.getCurrentStock()).isEqualTo(4);
        verify(kioscoMovementRepository, times(2)).save(any(KioscoMovementEntity.class));
    }

    @Test
    void devolucionCliente_referenciaFacturaOriginal() throws Exception {
        KioscoStockEntity stock = stockEntity(4, 0);
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));
        ArgumentCaptor<KioscoMovementEntity> captor = ArgumentCaptor.forClass(KioscoMovementEntity.class);

        service.registrarDevolucionCliente(locationId, productId, colorId, 2, 555L, true, userId);

        verify(kioscoMovementRepository).save(captor.capture());
        assertThat(captor.getValue().getReferenceId()).isEqualTo(555L);
    }

    @Test
    void traslado_restaOrigen_sumaDestino_yComparteReferencia() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(10, 0)));
        when(kioscoStockRepository.findForUpdate(locationDestId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(5, 0)));

        KioscoInventoryService.TrasladoResult result =
                service.registrarTrasladoDesdeIntegracion(
                        locationId, locationDestId, productId, colorId, new BigDecimal("3"), userId, 55L);

        assertThat(result.getOriginStock().getCurrentStock()).isEqualTo(7);
        assertThat(result.getDestinationStock().getCurrentStock()).isEqualTo(8);
        assertThat(result.getReferenceId()).isEqualTo(55L);
    }

    @Test
    void traslado_falla_siStockInsuficienteEnOrigen() {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(2, 0)));

        assertThatThrownBy(() -> service.registrarTrasladoDesdeIntegracion(
                        locationId, locationDestId, productId, colorId, new BigDecimal("3"), userId, 55L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void traslado_falla_siOrigenODestinoNoKiosco() {
        when(kioskInventoryGuard.isKioskLocation(any(LocationEntity.class))).thenReturn(false);

        assertThatThrownBy(() -> service.registrarTrasladoDesdeIntegracion(
                        locationId, locationDestId, productId, colorId, new BigDecimal("2"), userId, 55L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void traslado_falla_siSegundaOperacionFalla_yPropagaError() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(10, 0)));
        when(kioscoStockRepository.findForUpdate(locationDestId, productId, colorId))
                .thenThrow(new RuntimeException("destino lock error"));

        assertThatThrownBy(() -> service.registrarTrasladoDesdeIntegracion(
                        locationId, locationDestId, productId, colorId, new BigDecimal("3"), userId, 55L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("destino lock error");
    }

    @Test
    void venta_noOp_siVentaYaAplicadaParaMismaFactura() throws Exception {
        when(kioscoMovementRepository.existsPosReferenceMovement(
                locationId, productId, colorId, 777L, KioscoMovementType.VENTA, null))
                .thenReturn(true);
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                eq(locationId), eq(productId), eq(colorId), any()))
                .thenReturn(Optional.of(stockEntity(7, 0)));

        KioscoStockResponse response = service.registrarVenta(locationId, productId, colorId, 3, 777L, userId);

        assertThat(response.getCurrentStock()).isEqualTo(7);
        verify(kioscoMovementRepository, never()).save(any());
        verify(kioscoStockRepository, never()).save(any());
    }

    @Test
    void anulacion_noOp_siAnulacionYaAplicadaParaMismaFactura() throws Exception {
        when(kioscoMovementRepository.existsPosReferenceMovement(
                locationId, productId, colorId, 900L, KioscoMovementType.ANULACION, null))
                .thenReturn(true);
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                eq(locationId), eq(productId), eq(colorId), any()))
                .thenReturn(Optional.of(stockEntity(10, 0)));

        KioscoStockResponse response = service.anularFactura(
                900L, locationId, productId, colorId, 2, "error", false, userId);

        assertThat(response.getCurrentStock()).isEqualTo(10);
        verify(kioscoMovementRepository, never()).save(any());
        verify(kioscoStockRepository, never()).save(any());
    }

    @Test
    void trasladoIntegracion_noOp_siAmbosLadosYaAplicados() throws Exception {
        Long transferId = 55L;
        when(kioscoMovementRepository.existsInventoryTransferMovement(
                locationId, productId, colorId, transferId, KioscoMovementType.TRASLADO_SALIDA))
                .thenReturn(true);
        when(kioscoMovementRepository.existsInventoryTransferMovement(
                locationDestId, productId, colorId, transferId, KioscoMovementType.TRASLADO_ENTRADA))
                .thenReturn(true);
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                eq(locationId), eq(productId), eq(colorId), any()))
                .thenReturn(Optional.of(stockEntity(7, 0)));
        when(kioscoStockRepository.findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                eq(locationDestId), eq(productId), eq(colorId), any()))
                .thenReturn(Optional.of(stockEntity(8, 0)));

        KioscoInventoryService.TrasladoResult result = service.registrarTrasladoDesdeIntegracion(
                locationId, locationDestId, productId, colorId, new BigDecimal("3"), userId, transferId);

        assertThat(result.getReferenceId()).isEqualTo(transferId);
        assertThat(result.getOriginStock().getCurrentStock()).isEqualTo(7);
        assertThat(result.getDestinationStock().getCurrentStock()).isEqualTo(8);
        verify(kioscoMovementRepository, never()).save(any());
        verify(kioscoStockRepository, never()).save(any());
    }

    @Test
    void trasladoBoleta_rechazaLineaDuplicadaEnMismaSolicitud() {
        when(kioscoMovementRepository.findByPhysicalSlipNumber("BT-DUP")).thenReturn(List.of());
        when(kioscoMovementRepository.existsTrasladoBoletaDuplicateLine(any(), any(), any(), any(), any()))
                .thenReturn(false);

        KioscoInventoryTrasladoRequest request = KioscoInventoryTrasladoRequest.builder()
                .locationOriginId(locationId)
                .locationDestinationId(locationDestId)
                .userId(userId)
                .physicalSlipNumber("BT-DUP")
                .items(List.of(
                        KioscoInventoryTrasladoRequest.Item.builder()
                                .productId(productId).colorId(colorId).quantity(2).sizeKey("32").build(),
                        KioscoInventoryTrasladoRequest.Item.builder()
                                .productId(productId).colorId(colorId).quantity(2).sizeKey("32").build()
                ))
                .build();

        assertThatThrownBy(() -> service.registrarTraslado(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("línea duplicada");
    }

    @Test
    void trasladoBoleta_rechazaLineaYaRegistradaEnBoleta() {
        when(kioscoMovementRepository.findByPhysicalSlipNumber("BT-EXIST")).thenReturn(List.of());
        when(kioscoMovementRepository.existsTrasladoBoletaDuplicateLine(
                "BT-EXIST", productId, colorId, "32", 2))
                .thenReturn(true);

        KioscoInventoryTrasladoRequest request = KioscoInventoryTrasladoRequest.builder()
                .locationOriginId(locationId)
                .locationDestinationId(locationDestId)
                .userId(userId)
                .physicalSlipNumber("BT-EXIST")
                .items(List.of(
                        KioscoInventoryTrasladoRequest.Item.builder()
                                .productId(productId).colorId(colorId).quantity(2).sizeKey("32").build()
                ))
                .build();

        assertThatThrownBy(() -> service.registrarTraslado(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya está registrada");
    }

    @Test
    void merma_restaStock_yRegistraMotivo() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(7, 0)));
        ArgumentCaptor<KioscoMovementEntity> captor = ArgumentCaptor.forClass(KioscoMovementEntity.class);

        KioscoStockResponse response = service.registrarMerma(locationId, productId, colorId, 2, "producto vencido", userId);

        assertThat(response.getCurrentStock()).isEqualTo(5);
        verify(kioscoMovementRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).contains("vencido");
    }

    @Test
    void merma_falla_siMotivoVacio() {
        assertThatThrownBy(() -> service.registrarMerma(locationId, productId, colorId, 2, " ", userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void merma_falla_siStockInsuficiente() {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(1, 0)));
        assertThatThrownBy(() -> service.registrarMerma(locationId, productId, colorId, 2, "x", userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ajuste_positivo_y_negativo_y_cero() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(5, 0)));

        KioscoStockResponse positive = service.registrarAjuste(locationId, productId, colorId, 8, "conteo", userId);
        assertThat(positive.getCurrentStock()).isEqualTo(8);

        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(8, 0)));
        KioscoStockResponse negative = service.registrarAjuste(locationId, productId, colorId, 3, "conteo", userId);
        assertThat(negative.getCurrentStock()).isEqualTo(3);

        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(3, 0)));
        KioscoStockResponse zero = service.registrarAjuste(locationId, productId, colorId, 3, "conteo", userId);
        assertThat(zero.getCurrentStock()).isEqualTo(3);
    }

    @Test
    void ajuste_falla_siMotivoVacio() {
        assertThatThrownBy(() -> service.registrarAjuste(locationId, productId, colorId, 3, "", userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ajuste_foss_conRealSizes_actualizaSizesData() throws Exception {
        when(productRepository.findById(productId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(productId)
                .code("FOSS-15")
                .name("CINCHO FOSS 15")
                .build()));
        KioscoStockEntity stock = stockEntity(10, 0);
        stock.setSizesData("{\"32\":4,\"34\":6}");
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        Map<String, Integer> realSizes = new LinkedHashMap<>();
        realSizes.put("32", 3);
        realSizes.put("34", 4);

        KioscoStockResponse response = service.registrarAjuste(
                locationId, productId, colorId, 7, realSizes, "conteo fisico", userId);

        assertThat(response.getCurrentStock()).isEqualTo(7);
        assertThat(response.getSizes().get("32")).isEqualByComparingTo("3");
        assertThat(response.getSizes().get("34")).isEqualByComparingTo("4");
    }

    @Test
    void venta_falla_siHayDesglosePorTallaSinIndicarTalla() {
        KioscoStockEntity stock = stockEntity(5, 0);
        stock.setSizesData("{\"32\":5}");
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.registrarVenta(locationId, productId, colorId, 1, 100L, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Indique la talla");
    }

    @Test
    void venta_conTalla_descuentaSizesData() throws Exception {
        KioscoStockEntity stock = stockEntity(5, 0);
        stock.setSizesData("{\"32\":5}");
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId)).thenReturn(Optional.of(stock));

        KioscoStockResponse response = service.registrarVenta(locationId, productId, colorId, 2, 100L, userId, "32");

        assertThat(response.getCurrentStock()).isEqualTo(3);
        assertThat(response.getSizes().get("32")).isEqualByComparingTo("3");
    }

    @Test
    void anulacion_restauraStock_siProductoNoSalio() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(10, 0)));

        KioscoStockResponse response = service.anularFactura(900L, locationId, productId, colorId, 2, "error", false, userId);

        assertThat(response.getCurrentStock()).isEqualTo(12);
    }

    @Test
    void anulacion_noModificaStock_siProductoSiSalio() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(10, 0)));
        ArgumentCaptor<KioscoMovementEntity> captor = ArgumentCaptor.forClass(KioscoMovementEntity.class);

        KioscoStockResponse response = service.anularFactura(900L, locationId, productId, colorId, 2, "error", true, userId);

        assertThat(response.getCurrentStock()).isEqualTo(10);
        verify(kioscoMovementRepository).save(captor.capture());
        assertThat(captor.getValue().getMovementType()).isEqualTo(KioscoMovementType.ANULACION);
        assertThat(captor.getValue().getAffectsStock()).isFalse();
        assertThat(captor.getValue().getReferenceId()).isEqualTo(900L);
    }

    @Test
    void anulacion_falla_siMotivoVacio() {
        assertThatThrownBy(() -> service.anularFactura(1L, locationId, productId, colorId, 1, " ", true, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invariantes_stockAntesMasDeltaIgualStockDespues_yAppendOnly() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(5, 0)));
        ArgumentCaptor<KioscoMovementEntity> captor = ArgumentCaptor.forClass(KioscoMovementEntity.class);

        service.registrarVenta(locationId, productId, colorId, 2, 123L, userId);

        verify(kioscoMovementRepository).save(captor.capture());
        KioscoMovementEntity m = captor.getValue();
        assertThat(m.getStockBefore() - m.getQuantity()).isEqualTo(m.getStockAfter());
        verify(kioscoMovementRepository, never()).deleteById(anyLong());
    }

    @Test
    void kardex_clasificaMovimientosPorCategoria_yCuadraInventarioFinal() throws Exception {
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(stockEntity(0, 0)));

        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        LocalDateTime periodStart = from.atStartOfDay();

        KioscoMovementEntity prePeriodEntrada = movement(KioscoMovementType.ENTRADA, 0, 20, 1900L);
        List<KioscoMovementEntity> periodMoves = List.of(
                movement(KioscoMovementType.AJUSTE, 20, 25, 1901L),
                movement(KioscoMovementType.AJUSTE, 25, 22, 1902L),
                movement(KioscoMovementType.DEVOLUCION_CLIENTE, 22, 24, 1903L),
                movement(KioscoMovementType.ENTRADA, 24, 34, 1904L),
                movement(KioscoMovementType.TRASLADO_ENTRADA, 34, 38, 1905L),
                movement(KioscoMovementType.VENTA, 38, 32, 1906L),
                movement(KioscoMovementType.ANULACION, 32, 38, 1907L),
                movement(KioscoMovementType.DEVOLUCION_DEPOSITO, 38, 37, 1908L),
                movement(KioscoMovementType.TRASLADO_SALIDA, 37, 35, 1909L),
                movement(KioscoMovementType.MERMA, 35, 34, 1910L)
        );

        when(kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(eq(locationId), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    LocalDateTime cutoff = invocation.getArgument(1);
                    if (!cutoff.isAfter(periodStart)) {
                        return List.of(prePeriodEntrada);
                    }
                    java.util.ArrayList<KioscoMovementEntity> all = new java.util.ArrayList<>();
                    all.add(prePeriodEntrada);
                    all.addAll(periodMoves);
                    return all;
                });

        when(kioscoMovementRepository.findByLocationAndCreatedAtBetween(eq(locationId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(periodMoves);

        KioscoKardexReportResponse report = service.getKardexReport(locationId, from, to);

        assertThat(report.getRows()).hasSize(1);
        KioscoKardexReportResponse.KioscoKardexRow row = report.getRows().get(0);
        assertThat(row.getInventarioInicial()).isEqualTo(20);
        assertThat(row.getComprasAjustes()).isEqualTo(7);
        assertThat(row.getAnulacionCompras()).isEqualTo(3);
        assertThat(row.getEntradas()).isEqualTo(14);
        assertThat(row.getVentas()).isEqualTo(6);
        assertThat(row.getAnulacionVenta()).isEqualTo(6);
        assertThat(row.getSalida()).isEqualTo(4);
        assertThat(row.getInventarioFinal()).isEqualTo(34);

        assertThat(report.getTotals().getInventarioFinal()).isEqualTo(34);
    }

    @Test
    void kardex_falla_siRangoDeFechasInvertido() {
        assertThatThrownBy(() -> service.getKardexReport(locationId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("posterior");
    }

    @Test
    void buildKardexRows_conBalanceAsOf_replayHastaFechaExcluyeVentasPosteriores() throws Exception {
        KioscoStockEntity stock = stockEntity(5, 0);
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(stock));

        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        KioscoMovementEntity entradaPrevia = movementAt(KioscoMovementType.ENTRADA, 0, 10,
                LocalDateTime.of(2026, 5, 20, 10, 0));
        KioscoMovementEntity ventaAntesDelCorte = movementAt(KioscoMovementType.VENTA, 10, 7,
                LocalDateTime.of(2026, 6, 10, 12, 0));
        KioscoMovementEntity ventaDespuesDelCorte = movementAt(KioscoMovementType.VENTA, 7, 5,
                LocalDateTime.of(2026, 6, 20, 12, 0));

        when(kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(eq(locationId), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    LocalDateTime cutoff = invocation.getArgument(1);
                    return List.of(entradaPrevia, ventaAntesDelCorte, ventaDespuesDelCorte).stream()
                            .filter(m -> m.getCreatedAt().isBefore(cutoff))
                            .toList();
                });

        when(kioscoMovementRepository.findByLocationAndCreatedAtBetween(
                eq(locationId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(ventaAntesDelCorte));

        // Period end (to=asOf) excludes the June 20 sale → ledger final 7, not live current_stock 5.
        List<KioscoKardexReportResponse.KioscoKardexRow> rowsLive = service.buildKardexRows(
                locationId, from, asOf, false, null);
        assertThat(rowsLive).hasSize(1);
        assertThat(rowsLive.get(0).getInventarioFinal()).isEqualTo(7);

        List<KioscoKardexReportResponse.KioscoKardexRow> rowsAsOf = service.buildKardexRows(
                locationId, from, asOf, false, asOf);
        assertThat(rowsAsOf).hasSize(1);
        assertThat(rowsAsOf.get(0).getInventarioFinal()).isEqualTo(7);
        assertThat(rowsAsOf.get(0).getVentas()).isEqualTo(3);
    }

    private KioscoMovementEntity movementAt(
            KioscoMovementType type, int stockBefore, int stockAfter, LocalDateTime createdAt
    ) {
        return KioscoMovementEntity.builder()
                .id(2000L + createdAt.getDayOfMonth())
                .kioscoStockId(100L)
                .movementType(type)
                .quantity(Math.abs(stockAfter - stockBefore))
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .affectsStock(true)
                .userId(userId)
                .createdAt(createdAt)
                .build();
    }

    private KioscoMovementEntity movement(KioscoMovementType type, int stockBefore, int stockAfter) {
        return movement(type, stockBefore, stockAfter, 2000L);
    }

    private KioscoMovementEntity movement(KioscoMovementType type, int stockBefore, int stockAfter, long id) {
        return KioscoMovementEntity.builder()
                .id(id)
                .kioscoStockId(100L)
                .movementType(type)
                .quantity(Math.abs(stockAfter - stockBefore))
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .affectsStock(true)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private KioscoMovementEntity movementWithSize(KioscoMovementType type, int quantity, String sizeKey) {
        return KioscoMovementEntity.builder()
                .id(2100L + quantity + (sizeKey != null ? sizeKey.hashCode() : 0))
                .kioscoStockId(100L)
                .movementType(type)
                .quantity(quantity)
                .sizeKey(sizeKey)
                .stockBefore(0)
                .stockAfter(quantity)
                .affectsStock(true)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private KioscoMovementEntity shipmentEntrada(long id, int quantity) {
        return KioscoMovementEntity.builder()
                .id(id)
                .kioscoStockId(100L)
                .movementType(KioscoMovementType.ENTRADA)
                .quantity(quantity)
                .stockBefore(0)
                .stockAfter(quantity)
                .affectsStock(true)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private KioscoStockEntity stockEntity(int current, int minimum) {
        return KioscoStockEntity.builder()
                .id(100L)
                .locationId(locationId)
                .productId(productId)
                .colorId(colorId)
                .currentStock(current)
                .minimumStock(minimum)
                .lastUpdatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void pruneExcessShipmentEntradas_keepsOldestAndRemovesDuplicates() throws BusinessException {
        List<KioscoMovementEntity> entradas = List.of(
                shipmentEntrada(1L, 1),
                shipmentEntrada(2L, 1),
                shipmentEntrada(3L, 1),
                shipmentEntrada(4L, 1));

        int removed = service.pruneExcessShipmentEntradas(entradas, 1);

        assertThat(removed).isEqualTo(3);
        verify(entityManager, times(3)).createNativeQuery("DELETE FROM kiosco_movement WHERE id = :id");
        verify(entityManager, never()).createNativeQuery(
                org.mockito.ArgumentMatchers.eq("UPDATE kiosco_movement SET quantity = :qty, stock_after = stock_before + :qty WHERE id = :id"));
    }

    @Test
    void pruneExcessShipmentEntradas_trimsSingleOversizedEntrada() throws BusinessException {
        KioscoMovementEntity oversized = shipmentEntrada(10L, 25);
        oversized.setStockBefore(0);
        oversized.setStockAfter(25);

        int removed = service.pruneExcessShipmentEntradas(List.of(oversized), 10);

        assertThat(removed).isEqualTo(1);
        assertThat(oversized.getQuantity()).isEqualTo(10);
        assertThat(oversized.getStockAfter()).isEqualTo(10);
        verify(entityManager).createNativeQuery(
                "UPDATE kiosco_movement SET quantity = :qty, stock_after = stock_before + :qty WHERE id = :id");
        verify(entityManager, never()).createNativeQuery("DELETE FROM kiosco_movement WHERE id = :id");
    }

    @Test
    void deleteShipmentReconcileMermaMovements_deletesOnlyCuadreRows() throws BusinessException {
        KioscoMovementEntity merma = KioscoMovementEntity.builder()
                .id(55L)
                .kioscoStockId(100L)
                .movementType(KioscoMovementType.MERMA)
                .quantity(5)
                .reason("Cuadre recepción envío · SHIPMENT_RCPT:ENV#L1")
                .build();
        when(kioscoMovementRepository.findShipmentReconcileMermaMovements(
                locationId, 700L, "ENV#L1", productId, colorId)).thenReturn(List.of(merma));

        int removed = service.deleteShipmentReconcileMermaMovements(
                locationId, 700L, "ENV#L1", productId, colorId);

        assertThat(removed).isEqualTo(1);
        verify(entityManager).createNativeQuery("DELETE FROM kiosco_movement WHERE id = :id");
    }

    @Test
    void syncFossCurrentStockFromSizes_alignsUndercountedTotal() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(productId)
                .code("FOSS-15")
                .name("CINCHO FOSS 15")
                .build()));
        KioscoStockEntity stock = stockEntity(10, 0);
        stock.setId(100L);
        stock.setSizesData("{\"32\":4,\"34\":4,\"36\":4,\"38\":4,\"40\":2,\"42\":2}");

        service.syncFossCurrentStockFromSizes(stock);

        assertThat(stock.getCurrentStock()).isEqualTo(20);
    }

    @Test
    void reconcileStaleSizeBreakdown_clearsSizesWhenExceedCurrentStock() {
        // sizes sum 13 > current 10 → rebuild; no sized movements → clear stale breakdown.
        KioscoStockEntity stock = stockEntity(10, 0);
        stock.setId(100L);
        stock.setSizesData("{\"18\":2,\"20\":4,\"22\":4,\"24\":2,\"26\":1}");
        when(kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of());

        service.reconcileStaleSizeBreakdown(stock);

        assertThat(stock.getSizesData()).isNull();
        assertThat(stock.getCurrentStock()).isEqualTo(10);
    }

    @Test
    void replayStockLedgerRecalculatesFromMovements() {
        KioscoStockEntity stock = stockEntity(99, 0);
        KioscoMovementEntity entrada = movement(KioscoMovementType.ENTRADA, 0, 10);
        KioscoMovementEntity venta = movement(KioscoMovementType.VENTA, 10, 7);
        when(kioscoStockRepository.findById(100L)).thenReturn(Optional.of(stock));
        when(kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(entrada, venta));

        int rows = service.replayStockLedger(100L);

        assertThat(rows).isEqualTo(1);
        assertThat(stock.getCurrentStock()).isEqualTo(7);
        verify(kioscoMovementRepository, never()).save(any(KioscoMovementEntity.class));
        verify(kioscoStockRepository).save(stock);
    }

    @Test
    void rebuildSizes_pureSized_setsCurrentToSizesSum() {
        KioscoStockEntity stock = stockEntity(0, 0);
        KioscoMovementEntity e32 = movementWithSize(KioscoMovementType.ENTRADA, 5, "32");
        KioscoMovementEntity e34 = movementWithSize(KioscoMovementType.ENTRADA, 3, "34");
        KioscoMovementEntity v32 = movementWithSize(KioscoMovementType.VENTA, 2, "32");

        service.rebuildSizesDataFromMovements(stock, List.of(e32, e34, v32));

        assertThat(stock.getCurrentStock()).isEqualTo(6);
        assertThat(ProductInventorySizesJson.parse(stock.getSizesData()).get("32"))
                .isEqualByComparingTo("3");
        assertThat(ProductInventorySizesJson.parse(stock.getSizesData()).get("34"))
                .isEqualByComparingTo("3");
    }

    @Test
    void rebuildSizes_pureUnsized_clearsSizesSetsLedgerCurrent() {
        KioscoStockEntity stock = stockEntity(99, 0);
        stock.setSizesData("{\"32\":5}");
        KioscoMovementEntity entrada = movement(KioscoMovementType.ENTRADA, 0, 10);
        KioscoMovementEntity venta = movement(KioscoMovementType.VENTA, 10, 7);

        service.rebuildSizesDataFromMovements(stock, List.of(entrada, venta));

        assertThat(stock.getSizesData()).isNull();
        assertThat(stock.getCurrentStock()).isEqualTo(7);
    }

    @Test
    void rebuildSizes_mixedSizedAndUnsized_keepsFullLedgerTotal() {
        KioscoStockEntity stock = stockEntity(0, 0);
        // Unsized opening + sized entradas/ventas.
        KioscoMovementEntity unsizedOpen = movement(KioscoMovementType.ENTRADA, 0, 10);
        KioscoMovementEntity sizedIn = movementWithSize(KioscoMovementType.ENTRADA, 4, "32");
        KioscoMovementEntity sizedOut = movementWithSize(KioscoMovementType.VENTA, 1, "32");

        service.rebuildSizesDataFromMovements(stock, List.of(unsizedOpen, sizedIn, sizedOut));

        assertThat(ProductInventorySizesJson.parse(stock.getSizesData()).get("32"))
                .isEqualByComparingTo("3");
        // Full chain: 10 + 4 - 1 = 13 (not sizes-only 3).
        assertThat(stock.getCurrentStock()).isEqualTo(13);
    }

    @Test
    void replayMovementStockChain_clampsNegativeAndAnnotatesReason() {
        KioscoStockEntity stock = stockEntity(0, 0);
        KioscoMovementEntity entrada = movement(KioscoMovementType.ENTRADA, 0, 2);
        entrada.setId(1L);
        KioscoMovementEntity oversell = movement(KioscoMovementType.VENTA, 2, 0);
        oversell.setId(2L);
        oversell.setQuantity(5); // delta -5 → rawAfter -3
        when(kioscoStockRepository.findById(100L)).thenReturn(Optional.of(stock));
        when(kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(entrada, oversell));

        int rows = service.replayMovementStockChain(100L);

        assertThat(rows).isEqualTo(1);
        assertThat(stock.getCurrentStock()).isEqualTo(0);
        assertThat(entrada.getStockBefore()).isEqualTo(0);
        assertThat(entrada.getStockAfter()).isEqualTo(2);
        assertThat(oversell.getStockBefore()).isEqualTo(2);
        assertThat(oversell.getStockAfter()).isEqualTo(0);
        assertThat(oversell.getReason()).contains(KioscoInventoryService.REPLAY_CLAMP_REASON_MARKER);
        assertThat(oversell.getReason()).contains("rawAfter=-3");
        verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.contains("reason = :reason"));
    }

    @Test
    void resolveInventarioFinal_fossMixed_prefersLedgerWhenAboveSizes() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(productId)
                .code("FOSS-15")
                .name("CINCHO FOSS 15")
                .build()));
        KioscoStockEntity stock = stockEntity(13, 0);
        stock.setSizesData("{\"32\":3}");

        assertThat(service.resolveInventarioFinal(stock, null)).isEqualTo(13);
    }

    @Test
    void computePrePeriodEntradasByStockId_sumaEntradasAntesDelPeriodo() throws Exception {
        LocalDateTime periodStart = LocalDate.of(2026, 6, 1).atStartOfDay();
        KioscoMovementEntity entradaPrevia = movementAt(KioscoMovementType.ENTRADA, 0, 1,
                LocalDateTime.of(2026, 5, 28, 10, 0));
        KioscoMovementEntity ventaPrevia = movementAt(KioscoMovementType.VENTA, 1, 0,
                LocalDateTime.of(2026, 5, 29, 10, 0));
        when(kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(eq(locationId), eq(periodStart)))
                .thenReturn(List.of(entradaPrevia, ventaPrevia));

        Map<Long, Integer> entradas = service.computePrePeriodEntradasByStockId(locationId, null, periodStart);

        assertThat(entradas).containsEntry(100L, 1);
    }

    @Test
    void initializeMissingStock_createsColorVariantsAndCinchoSizes() throws Exception {
        ProductEntity regular = ProductEntity.builder().id(1L).code("BOL-01").name("Bolso").build();
        ProductEntity cincho = ProductEntity.builder()
                .id(2L)
                .code("FOSS-01")
                .name("Cincho")
                .cinchoType("CASUAL")
                .cinchoForKids(false)
                .build();
        when(productRepository.findAll()).thenReturn(List.of(regular, cincho));
        when(colorRepository.findAll()).thenReturn(List.of(
                ColorEntity.builder().id(2L).name("CAFE").build(),
                ColorEntity.builder().id(3L).name("NEGRO").build(),
                ColorEntity.builder().id(13L).name("GENA").build(),
                ColorEntity.builder().id(37L).name("NEGRO/CAFE").build(),
                ColorEntity.builder().id(38L).name("NEGRO/GENA").build(),
                ColorEntity.builder().id(39L).name("CAFE/GENA").build()
        ));
        when(kioscoStockRepository.findByLocationIdIn(List.of(locationId))).thenReturn(List.of());
        when(kioscoStockRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        KioscoInventoryInitializeResponse result = service.initializeMissingStock(locationId, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KioscoStockEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(kioscoStockRepository).saveAll(captor.capture());
        List<KioscoStockEntity> created = captor.getValue();

        assertThat(result.getCreatedCount()).isEqualTo(created.size());
        assertThat(created.stream().filter(s -> s.getProductId().equals(1L)).count()).isEqualTo(3);
        assertThat(created.stream().filter(s -> s.getProductId().equals(2L)).count()).isEqualTo(6);
        assertThat(created.stream()
                .filter(s -> s.getProductId().equals(2L))
                .allMatch(s -> s.getSizesData() != null && s.getSizesData().contains("\"32\":0")))
                .isTrue();
    }

    @Test
    void initializeMissingStock_packagingProduct_singleRowWithoutColorOrSizes() throws Exception {
        ProductEntity packaging = ProductEntity.builder().id(3L).code("SUM-001").name("Bolsa").build();
        when(productRepository.findAll()).thenReturn(List.of(packaging));
        when(colorRepository.findAll()).thenReturn(List.of(
                ColorEntity.builder().id(2L).name("CAFE").build(),
                ColorEntity.builder().id(3L).name("NEGRO").build()
        ));
        when(kioscoStockRepository.findByLocationIdIn(List.of(locationId))).thenReturn(List.of());
        when(kioscoStockRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        KioscoInventoryInitializeResponse result = service.initializeMissingStock(locationId, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KioscoStockEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(kioscoStockRepository).saveAll(captor.capture());
        List<KioscoStockEntity> created = captor.getValue();

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getProductId()).isEqualTo(3L);
        assertThat(created.get(0).getColorId()).isNull();
        assertThat(created.get(0).getSizesData()).isNull();
    }

    @Test
    void initializeMissingStock_skipsLegacyRowsWithoutHardwareColumn() throws Exception {
        ProductEntity regular = ProductEntity.builder().id(1L).code("BOL-01").name("Bolso").build();
        when(productRepository.findAll()).thenReturn(List.of(regular));
        when(colorRepository.findAll()).thenReturn(List.of(
                ColorEntity.builder().id(2L).name("CAFE").build(),
                ColorEntity.builder().id(3L).name("NEGRO").build()
        ));
        when(kioscoStockRepository.findByLocationIdIn(List.of(locationId))).thenReturn(List.of(
                KioscoStockEntity.builder()
                        .locationId(locationId)
                        .productId(1L)
                        .colorId(2L)
                        .hardwareCondition(null)
                        .build()
        ));
        when(kioscoStockRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        KioscoInventoryInitializeResponse result = service.initializeMissingStock(locationId, userId);

        assertThat(result.getCreatedCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KioscoStockEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(kioscoStockRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(KioscoStockEntity::getColorId).containsExactly(3L);
    }
}
