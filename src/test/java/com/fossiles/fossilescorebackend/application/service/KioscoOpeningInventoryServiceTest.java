package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryApplyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryStatusResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoOpeningInventoryItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoOpeningInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioscoOpeningInventoryServiceTest {

    @Mock
    private KioscoOpeningInventoryRepository openingInventoryRepository;
    @Mock
    private KioscoOpeningInventoryItemRepository openingInventoryItemRepository;
    @Mock
    private KioscoInventoryService kioscoInventoryService;
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

    @InjectMocks
    private KioscoOpeningInventoryService service;

    private final Long locationId = 10L;
    private final Long sessionId = 100L;
    private final Long productId = 30L;
    private final Long packagingProductId = 31L;
    private final Long fossProductId = 32L;
    private final Long colorId = 40L;
    private final Long userId = 50L;

    @BeforeEach
    void setUp() {
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(LocationEntity.builder()
                .id(locationId)
                .name("Kiosko A")
                .code("K-A")
                .build()));
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder()
                .id(userId)
                .username("supervisora")
                .build()));
        when(openingInventoryRepository.save(any(KioscoOpeningInventoryEntity.class)))
                .thenAnswer(inv -> {
                    KioscoOpeningInventoryEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(sessionId);
                    }
                    if (entity.getCreatedAt() == null) {
                        entity.setCreatedAt(LocalDateTime.now());
                    }
                    if (entity.getUpdatedAt() == null) {
                        entity.setUpdatedAt(LocalDateTime.now());
                    }
                    return entity;
                });
        when(kioscoMovementRepository.findByLocationIdOrderByCreatedAtDesc(locationId))
                .thenReturn(List.of());
    }

    @Test
    void startOrGetDraft_creaBorradorCuandoNoExiste() throws Exception {
        when(openingInventoryRepository.existsByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO)).thenReturn(false);
        when(openingInventoryRepository.findByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.DRAFT)).thenReturn(Optional.empty());
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of());

        KioscoOpeningInventoryReportResponse report = service.startOrGetDraft(locationId);

        assertThat(report.getId()).isEqualTo(sessionId);
        assertThat(report.getStatus()).isEqualTo("DRAFT");
        assertThat(report.getLocationId()).isEqualTo(locationId);
    }

    @Test
    void startOrGetDraft_bloqueaSiYaHayAplicado() {
        when(openingInventoryRepository.existsByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO)).thenReturn(true);

        assertThatThrownBy(() -> service.startOrGetDraft(locationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya tiene un inventario inicial aplicado");
    }

    @Test
    void upsertItems_rechazaEmpaqueConColor() {
        stubDraftSession();
        when(productRepository.findById(packagingProductId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(packagingProductId)
                .code("SUM-001")
                .name("Empaque")
                .build()));

        assertThatThrownBy(() -> service.upsertItems(sessionId, List.of(
                KioscoOpeningInventoryItemUpsertRequest.builder()
                        .productId(packagingProductId)
                        .colorId(colorId)
                        .quantity(5)
                        .build())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SUM-");
    }

    @Test
    void upsertItems_aceptaEmpaqueSinColor() throws Exception {
        stubDraftSession();
        when(productRepository.findById(packagingProductId)).thenReturn(Optional.of(ProductEntity.builder()
                .id(packagingProductId)
                .code("SUM-001")
                .name("Empaque")
                .build()));
        when(openingInventoryItemRepository.findByOpeningInventoryIdAndProductIdAndColorId(
                sessionId, packagingProductId, null)).thenReturn(Optional.empty());
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(KioscoOpeningInventoryItemEntity.builder()
                        .openingInventoryId(sessionId)
                        .productId(packagingProductId)
                        .quantity(5)
                        .build()));

        KioscoOpeningInventoryReportResponse report = service.upsertItems(sessionId, List.of(
                KioscoOpeningInventoryItemUpsertRequest.builder()
                        .productId(packagingProductId)
                        .quantity(5)
                        .build()));

        assertThat(report.getItems()).hasSize(1);
        verify(openingInventoryItemRepository).save(any(KioscoOpeningInventoryItemEntity.class));
    }

    @Test
    void upsertItems_fossRequiereTallas() {
        stubDraftSession();
        when(productRepository.findById(fossProductId)).thenReturn(Optional.of(fossProduct(fossProductId)));
        when(colorRepository.existsById(colorId)).thenReturn(true);

        assertThatThrownBy(() -> service.upsertItems(sessionId, List.of(
                KioscoOpeningInventoryItemUpsertRequest.builder()
                        .productId(fossProductId)
                        .colorId(colorId)
                        .quantity(3)
                        .build())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FOSS");
    }

    @Test
    void apply_creaAjustesYMarcaAplicado() throws Exception {
        stubDraftSession();
        KioscoOpeningInventoryItemEntity item = KioscoOpeningInventoryItemEntity.builder()
                .openingInventoryId(sessionId)
                .productId(productId)
                .colorId(colorId)
                .quantity(10)
                .build();
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(item));
        when(productRepository.findAllById(List.of(productId))).thenReturn(List.of(ProductEntity.builder()
                .id(productId)
                .code("CAM-001")
                .name("Camisa")
                .build()));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(KioscoStockEntity.builder()
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .currentStock(0)
                        .build()));
        when(kioscoInventoryService.registrarAjuste(
                eq(locationId),
                eq(productId),
                eq(colorId),
                eq(10),
                isNull(),
                eq(KioscoOpeningInventoryService.OPENING_INVENTORY_REASON),
                eq(userId)
        )).thenReturn(KioscoStockResponse.builder().currentStock(10).build());

        KioscoOpeningInventoryReportResponse report = service.apply(
                sessionId, KioscoOpeningInventoryApplyRequest.builder().userId(userId).build());

        assertThat(report.getStatus()).isEqualTo("APLICADO");
        verify(kioscoInventoryService).registrarAjuste(
                locationId, productId, colorId, 10, null,
                KioscoOpeningInventoryService.OPENING_INVENTORY_REASON, userId);
        ArgumentCaptor<KioscoOpeningInventoryEntity> captor =
                ArgumentCaptor.forClass(KioscoOpeningInventoryEntity.class);
        verify(openingInventoryRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(KioscoOpeningInventoryStatus.APLICADO);
    }

    @Test
    void apply_bloqueaSegundoApplyEnMismoKiosko() {
        stubDraftSession();
        when(openingInventoryRepository.existsByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO)).thenReturn(true);

        assertThatThrownBy(() -> service.apply(sessionId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya tiene un inventario inicial aplicado");
    }

    @Test
    void apply_fossEnviaRealSizes() throws Exception {
        stubDraftSession();
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("32", 2);
        sizes.put("34", 1);
        KioscoOpeningInventoryItemEntity item = KioscoOpeningInventoryItemEntity.builder()
                .openingInventoryId(sessionId)
                .productId(fossProductId)
                .colorId(colorId)
                .quantity(3)
                .sizesData("{\"32\":2,\"34\":1}")
                .build();
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(item));
        when(productRepository.findAllById(List.of(fossProductId))).thenReturn(List.of(fossProduct(fossProductId)));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(KioscoStockEntity.builder()
                        .locationId(locationId)
                        .productId(fossProductId)
                        .colorId(colorId)
                        .currentStock(0)
                        .sizesData("{\"32\":0,\"34\":0}")
                        .build()));

        service.apply(sessionId, null);

        verify(kioscoInventoryService).registrarAjuste(
                eq(locationId),
                eq(fossProductId),
                eq(colorId),
                eq(3),
                eq(sizes),
                eq(KioscoOpeningInventoryService.OPENING_INVENTORY_REASON),
                eq(userId));
    }

    @Test
    void apply_advierteMovimientosPrevios() throws Exception {
        stubDraftSession();
        when(kioscoMovementRepository.findByLocationIdOrderByCreatedAtDesc(locationId))
                .thenReturn(List.of(KioscoMovementEntity.builder()
                        .movementType(KioscoMovementType.ENTRADA)
                        .reason("Recepción envío")
                        .build()));
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(KioscoOpeningInventoryItemEntity.builder()
                        .openingInventoryId(sessionId)
                        .productId(productId)
                        .colorId(colorId)
                        .quantity(5)
                        .build()));
        when(productRepository.findAllById(List.of(productId))).thenReturn(List.of(ProductEntity.builder()
                .id(productId)
                .code("CAM-001")
                .name("Camisa")
                .build()));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(KioscoStockEntity.builder()
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .currentStock(0)
                        .build()));
        when(kioscoInventoryService.registrarAjuste(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(KioscoStockResponse.builder().currentStock(5).build());

        KioscoOpeningInventoryReportResponse report = service.apply(sessionId, null);

        assertThat(report.getWarnings()).isNotNull();
        assertThat(report.getWarnings().get(0)).contains("movimiento(s) distintos");
    }

    @Test
    void getStatus_retornaAplicadoCuandoExiste() throws Exception {
        when(openingInventoryRepository.findByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO))
                .thenReturn(Optional.of(KioscoOpeningInventoryEntity.builder()
                        .id(sessionId)
                        .locationId(locationId)
                        .status(KioscoOpeningInventoryStatus.APLICADO)
                        .appliedBy(userId)
                        .appliedAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                        .build()));
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(new KioscoOpeningInventoryItemEntity(), new KioscoOpeningInventoryItemEntity()));

        KioscoOpeningInventoryStatusResponse status = service.getStatus(locationId);

        assertThat(status.getStatus()).isEqualTo("APLICADO");
        assertThat(status.getAppliedId()).isEqualTo(sessionId);
        assertThat(status.getDraftItemCount()).isEqualTo(2);
    }

    @Test
    void apply_noLlamaAjusteSiStockYaCoincide() {
        stubDraftSession();
        when(openingInventoryItemRepository.findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(sessionId))
                .thenReturn(List.of(KioscoOpeningInventoryItemEntity.builder()
                        .openingInventoryId(sessionId)
                        .productId(productId)
                        .colorId(colorId)
                        .quantity(5)
                        .build()));
        when(productRepository.findAllById(List.of(productId))).thenReturn(List.of(ProductEntity.builder()
                .id(productId)
                .code("CAM-001")
                .name("Camisa")
                .build()));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId))
                .thenReturn(List.of(KioscoStockEntity.builder()
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .currentStock(5)
                        .build()));

        assertThatThrownBy(() -> service.apply(sessionId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Ningún ítem difiere");

        verify(kioscoInventoryService, never()).registrarAjuste(any(), any(), any(), any(), any(), any(), any());
    }

    private void stubDraftSession() {
        AtomicReference<KioscoOpeningInventoryEntity> sessionRef = new AtomicReference<>(
                KioscoOpeningInventoryEntity.builder()
                        .id(sessionId)
                        .locationId(locationId)
                        .status(KioscoOpeningInventoryStatus.DRAFT)
                        .createdBy(userId)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build());
        when(openingInventoryRepository.findById(sessionId)).thenAnswer(inv -> Optional.of(sessionRef.get()));
        when(openingInventoryRepository.existsByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO)).thenReturn(false);
    }

    private ProductEntity fossProduct(Long id) {
        return ProductEntity.builder()
                .id(id)
                .code("FOSS-001")
                .name("Cincho FOSS")
                .cinchoType("FOSS")
                .build();
    }
}
