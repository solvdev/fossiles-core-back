package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
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
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
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
import java.util.List;
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
    private KioskInventoryGuard kioskInventoryGuard;
    @Mock
    private ProductShipmentRepository productShipmentRepository;
    @Mock
    private ProductInventoryKardexRepository productInventoryKardexRepository;
    @Mock
    private InventoryTransferRepository inventoryTransferRepository;

    @InjectMocks
    private KioscoInventoryService service;

    private final Long locationId = 10L;
    private final Long locationDestId = 11L;
    private final Long productId = 30L;
    private final Long colorId = 40L;
    private final Long userId = 50L;

    @BeforeEach
    void setUp() {
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
        when(colorRepository.existsById(colorId)).thenReturn(true);
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
        assertThat(response.getSizes()).containsKey("32");
        assertThat(response.getSizes().get("32")).isEqualByComparingTo(new BigDecimal("4"));
        verify(productInventoryService, never()).incrementInventory(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
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
                service.registrarTraslado(locationId, locationDestId, productId, colorId, 3, userId);

        assertThat(result.getOriginStock().getCurrentStock()).isEqualTo(7);
        assertThat(result.getDestinationStock().getCurrentStock()).isEqualTo(8);
        assertThat(result.getReferenceId()).isNotNull();
    }

    @Test
    void traslado_falla_siStockInsuficienteEnOrigen() {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(2, 0)));

        assertThatThrownBy(() -> service.registrarTraslado(locationId, locationDestId, productId, colorId, 3, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void traslado_falla_siOrigenODestinoNoKiosco() {
        when(kioskInventoryGuard.isKioskLocation(any(LocationEntity.class))).thenReturn(false);

        assertThatThrownBy(() -> service.registrarTraslado(locationId, locationDestId, productId, colorId, 2, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void traslado_falla_siSegundaOperacionFalla_yPropagaError() throws Exception {
        when(kioscoStockRepository.findForUpdate(locationId, productId, colorId))
                .thenReturn(Optional.of(stockEntity(10, 0)));
        when(kioscoStockRepository.findForUpdate(locationDestId, productId, colorId))
                .thenThrow(new RuntimeException("destino lock error"));

        assertThatThrownBy(() -> service.registrarTraslado(locationId, locationDestId, productId, colorId, 3, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("destino lock error");
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
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(locationId))
                .thenReturn(List.of(stockEntity(0, 0)));

        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        when(kioscoMovementRepository.findByLocationAndCreatedAtBefore(eq(locationId), any(LocalDateTime.class)))
                .thenReturn(List.of(movement(KioscoMovementType.ENTRADA, 0, 20)));

        when(kioscoMovementRepository.findByLocationAndCreatedAtBetween(eq(locationId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        movement(KioscoMovementType.AJUSTE, 20, 25),
                        movement(KioscoMovementType.AJUSTE, 25, 22),
                        movement(KioscoMovementType.DEVOLUCION_CLIENTE, 22, 24),
                        movement(KioscoMovementType.ENTRADA, 24, 34),
                        movement(KioscoMovementType.TRASLADO_ENTRADA, 34, 38),
                        movement(KioscoMovementType.VENTA, 38, 32),
                        movement(KioscoMovementType.ANULACION, 32, 38),
                        movement(KioscoMovementType.DEVOLUCION_DEPOSITO, 38, 37),
                        movement(KioscoMovementType.TRASLADO_SALIDA, 37, 35),
                        movement(KioscoMovementType.MERMA, 35, 34)
                ));

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

    private KioscoMovementEntity movement(KioscoMovementType type, int stockBefore, int stockAfter) {
        return KioscoMovementEntity.builder()
                .id(2000L)
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
    void replayStockLedgerRecalculatesFromMovements() {
        KioscoStockEntity stock = stockEntity(99, 0);
        KioscoMovementEntity entrada = movement(KioscoMovementType.ENTRADA, 0, 10);
        KioscoMovementEntity venta = movement(KioscoMovementType.VENTA, 10, 7);
        when(kioscoStockRepository.findById(100L)).thenReturn(Optional.of(stock));
        when(kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(entrada, venta));

        int rows = service.replayStockLedger(100L);

        assertThat(rows).isEqualTo(1);
        assertThat(entrada.getStockBefore()).isZero();
        assertThat(entrada.getStockAfter()).isEqualTo(10);
        assertThat(venta.getStockBefore()).isEqualTo(10);
        assertThat(venta.getStockAfter()).isEqualTo(7);
        assertThat(stock.getCurrentStock()).isEqualTo(7);
        verify(kioscoMovementRepository, times(2)).save(any(KioscoMovementEntity.class));
        verify(kioscoStockRepository).save(stock);
    }
}
