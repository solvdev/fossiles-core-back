package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoNotificationRecipientRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDate;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioscoInventoryCountServiceTest {

    @Mock
    private KioscoPhysicalCountRepository countRepository;
    @Mock
    private KioscoPhysicalCountItemRepository itemRepository;
    @Mock
    private com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountPresenceRepository presenceRepository;
    @Mock
    private KioscoNotificationRecipientRepository notificationRecipientRepository;
    @Mock
    private KioscoInventoryService kioscoInventoryService;
    @Mock
    private KioscoStockRepository kioscoStockRepository;
    @Mock
    private KioskExchangeSlipRepository exchangeSlipRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductCategoryRepository productCategoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityUtil securityUtil;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private KioscoInventoryCountService service;

    private final Long locationId = 10L;
    private final Long productId = 30L;
    private final Long colorId = 40L;
    private final Long categoryId = 70L;
    private final Long userId = 50L;
    private final Long reviewerId = 60L;
    private final Long countId = 200L;
    private final LocalDate from = LocalDate.of(2026, 6, 1);
    private final LocalDate to = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void setUp() {
        when(locationRepository.findById(locationId)).thenReturn(Optional.of(LocationEntity.builder()
                .id(locationId)
                .name("Kiosko A")
                .code("K-A")
                .categoria("KIOSKO")
                .build()));
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder()
                .id(userId).username("paola").build()));
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(UserEntity.builder()
                .id(reviewerId).username("gustavo").build()));
        when(productRepository.findAllById(any())).thenReturn(List.of(ProductEntity.builder()
                .id(productId).code("P-1").name("Tarjetero").categoryId(categoryId).build()));
        when(productCategoryRepository.findAllById(any())).thenReturn(List.of(ProductCategoryEntity.builder()
                .id(categoryId).code("TARJ").name("Tarjeteros").build()));
        when(itemRepository.findByCountId(any())).thenReturn(List.of());
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of());
        when(exchangeSlipRepository.findByPhysicalCountId(any())).thenReturn(List.of());
        when(exchangeSlipRepository.findByKioskLocationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        try {
            when(kioscoInventoryService.computeStockBalanceByStockId(any(), any())).thenReturn(Map.of());
            when(kioscoInventoryService.computeSizeBalanceByStockAndSize(any(), any())).thenReturn(Map.of());
            when(kioscoInventoryService.computePrePeriodEntradasByStockId(any(), any(), any())).thenReturn(Map.of());
            when(kioscoInventoryService.computePrePeriodEntradasByStockAndSize(any(), any(), any())).thenReturn(Map.of());
        } catch (BusinessException | ResourceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private KioscoKardexReportResponse.KioscoKardexRow kardexRow(int inventarioFinal) {
        return KioscoKardexReportResponse.KioscoKardexRow.builder()
                .productId(productId)
                .productCode("P-1")
                .productName("Tarjetero")
                .colorId(colorId)
                .colorName("Cafe")
                .entradas(inventarioFinal)
                .build();
    }

    private void stubPrincipalKardex(List<KioscoKardexReportResponse.KioscoKardexRow> rows)
            throws BusinessException, ResourceNotFoundException {
        when(kioscoInventoryService.buildKardexRows(eq(locationId), eq(from), eq(to), eq(true), eq(to), eq(countId)))
                .thenReturn(rows);
        when(kioscoInventoryService.buildKardexByStockAndSize(eq(locationId), eq(from), eq(to), eq(countId)))
                .thenReturn(Map.of());
    }

    private void stubSubcountKardex(LocalDate asOf, List<KioscoKardexReportResponse.KioscoKardexRow> rows)
            throws BusinessException, ResourceNotFoundException {
        when(kioscoInventoryService.buildKardexRows(eq(locationId), eq(from), eq(asOf), eq(true), eq(asOf), eq(countId)))
                .thenReturn(rows);
        when(kioscoInventoryService.buildKardexByStockAndSize(eq(locationId), eq(from), eq(asOf), eq(countId)))
                .thenReturn(Map.of());
    }

    @Test
    void startOrGetSession_creaNuevaSesion_siNoExiste() throws Exception {
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });
        stubPrincipalKardex(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getId()).isEqualTo(countId);
        assertThat(report.getStatus()).isEqualTo("DRAFT");
        assertThat(report.getGeneratedByName()).isEqualTo("paola");
        assertThat(report.getCategories()).hasSize(1);
        assertThat(report.getCategories().get(0).getCategoryName()).isEqualTo("Tarjeteros");
        assertThat(report.getCategories().get(0).getRows()).hasSize(1);
        assertThat(report.getTotalGeneral().getInventarioFinal()).isEqualTo(10);
        assertThat(report.getTotalGeneral().getDiferencia()).isEqualTo(-10);
    }

    @Test
    void startOrGetSession_reusaSesionExistente_siYaExiste() throws Exception {
        KioscoPhysicalCountEntity existing = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .maxAbsDiff(10)
                .build();
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.of(existing));
        stubPrincipalKardex(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getId()).isEqualTo(countId);
        // maxAbsDiff (10) ya coincide con lo calculado, por lo que no hace falta persistir de nuevo.
        org.mockito.Mockito.verify(countRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void upsertItems_guardaConteo_yCalculaDiferencia() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        stubPrincipalKardex(List.of(kardexRow(10)));

        AtomicReference<KioscoPhysicalCountItemEntity> savedItemRef = new AtomicReference<>();
        when(itemRepository.findByCountIdAndProductIdAndColorId(countId, productId, colorId)).thenReturn(Optional.empty());
        when(itemRepository.save(any(KioscoPhysicalCountItemEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity item = inv.getArgument(0);
            if (item.getId() == null) {
                item.setId(900L);
            }
            savedItemRef.set(item);
            return item;
        });
        when(itemRepository.findByCountId(countId)).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity saved = savedItemRef.get();
            return saved != null ? List.of(saved) : List.of();
        });

        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .counts(java.util.Map.of("V1", 2, "V2", 1, "BO", 1))
                .build();

        KioscoPhysicalCountReportResponse report = service.upsertItems(countId, List.of(request));

        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getTotal()).isEqualTo(4);
        assertThat(row.getDiferencia()).isEqualTo(-6);
        assertThat(row.getCounts().get("V1")).isEqualTo(2);
    }

    @Test
    void upsertItems_guardaDesglosePorTalla() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        stubPrincipalKardex(List.of(kardexRow(10)));

        AtomicReference<KioscoPhysicalCountItemEntity> savedItemRef = new AtomicReference<>();
        when(itemRepository.findByCountIdAndProductIdAndColorId(countId, productId, colorId)).thenReturn(Optional.empty());
        when(itemRepository.save(any(KioscoPhysicalCountItemEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity item = inv.getArgument(0);
            if (item.getId() == null) {
                item.setId(900L);
            }
            savedItemRef.set(item);
            return item;
        });
        when(itemRepository.findByCountId(countId)).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity saved = savedItemRef.get();
            return saved != null ? List.of(saved) : List.of();
        });

        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .counts(java.util.Map.of("BO", 5))
                .physicalSizes(java.util.Map.of("28", 2, "30", 3))
                .build();

        KioscoPhysicalCountReportResponse report = service.upsertItems(countId, List.of(request));

        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getPhysicalSizes()).containsEntry("28", 2).containsEntry("30", 3);
        assertThat(row.getPhysicalSizesSummary()).contains("28: 2");
        assertThat(savedItemRef.get().getSizeCountsData()).contains("30");
        assertThat(savedItemRef.get().getSizeLocationCountsData()).isNull();
    }

    @Test
    void upsertItems_guardaDesgloseFossPorUbicacion() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        stubPrincipalKardex(List.of(kardexRow(10)));

        AtomicReference<KioscoPhysicalCountItemEntity> savedItemRef = new AtomicReference<>();
        when(itemRepository.findByCountIdAndProductIdAndColorId(countId, productId, colorId)).thenReturn(Optional.empty());
        when(itemRepository.save(any(KioscoPhysicalCountItemEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity item = inv.getArgument(0);
            if (item.getId() == null) {
                item.setId(900L);
            }
            savedItemRef.set(item);
            return item;
        });
        when(itemRepository.findByCountId(countId)).thenAnswer(inv -> {
            KioscoPhysicalCountItemEntity saved = savedItemRef.get();
            return saved != null ? List.of(saved) : List.of();
        });

        java.util.Map<String, java.util.Map<String, Integer>> byLocation = new java.util.LinkedHashMap<>();
        byLocation.put("E", java.util.Map.of("28", 2, "30", 3));
        byLocation.put("BO", java.util.Map.of("28", 1));

        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .counts(java.util.Map.of("E", 5, "BO", 1))
                .physicalSizes(java.util.Map.of("28", 3, "30", 3))
                .physicalSizesByLocation(byLocation)
                .build();

        KioscoPhysicalCountReportResponse report = service.upsertItems(countId, List.of(request));

        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getPhysicalSizesByLocation()).containsKey("E").containsKey("BO");
        assertThat(row.getPhysicalSizesByLocation().get("E")).containsEntry("28", 2);
        assertThat(row.getPhysicalSizesByLocation().get("BO")).containsEntry("28", 1);
        assertThat(savedItemRef.get().getSizeLocationCountsData()).contains("BO");
    }

    @Test
    void upsertItems_falla_siTallaNegativa() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder().id(countId).locationId(locationId).build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .physicalSizes(java.util.Map.of("28", -1))
                .build();

        assertThatThrownBy(() -> service.upsertItems(countId, List.of(request)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("talla");
    }

    @Test
    void upsertItems_falla_siConteoNegativo() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder().id(countId).locationId(locationId).build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .counts(java.util.Map.of("V1", -1))
                .build();

        assertThatThrownBy(() -> service.upsertItems(countId, List.of(request)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    void upsertItems_falla_siCountIdNoExiste() {
        when(countRepository.findById(countId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertItems(countId, List.of(KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId).build())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void terminarConteo_marcaComoContado_siEstaEnBorrador() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        stubPrincipalKardex(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.terminarConteo(countId);

        assertThat(report.getStatus()).isEqualTo("CONTADO");
        assertThat(count.getStatus()).isEqualTo(KioscoPhysicalCountStatus.CONTADO);
    }

    @Test
    void markReviewed_actualizaEstadoRevisorYNotas() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.CONTADO)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        when(securityUtil.getCurrentUserId()).thenReturn(reviewerId);
        stubPrincipalKardex(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.markReviewed(countId, "Revisar billeteras");

        assertThat(report.getStatus()).isEqualTo("REVISADO");
        assertThat(report.getReviewedByName()).isEqualTo("gustavo");
        assertThat(report.getNotes()).isEqualTo("Revisar billeteras");
        assertThat(count.getReviewedAt()).isNotNull();
    }

    @Test
    void listSessions_devuelveResumenConNombresDeUsuario() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.REVISADO)
                .generatedBy(userId)
                .reviewedBy(reviewerId)
                .build();
        when(countRepository.findByLocationIdOrderByGeneratedAtDesc(locationId)).thenReturn(List.of(count));

        var sessions = service.listSessions(locationId);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getGeneratedByName()).isEqualTo("paola");
        assertThat(sessions.get(0).getReviewedByName()).isEqualTo("gustavo");
    }

    @Test
    void cerrarConteo_marcaComoCerrado_siEstaRevisado() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.REVISADO)
                .generatedBy(userId)
                .reviewedBy(reviewerId)
                .maxAbsDiff(0)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        when(securityUtil.getCurrentUserId()).thenReturn(reviewerId);
        stubPrincipalKardex(List.of(kardexRow(10)));

        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        KioscoPhysicalCountReportResponse report = service.cerrarConteo(countId);

        assertThat(report.getStatus()).isEqualTo("CERRADO");
        assertThat(report.getClosedByName()).isEqualTo("gustavo");
        assertThat(count.getClosedAt()).isNotNull();
        assertThat(count.getClosedBy()).isEqualTo(reviewerId);
        assertThat(count.getClosingBalancesData()).isNotBlank();
        assertThat(count.getClosingBalancesData()).contains(productId + ":" + colorId);
    }

    @Test
    void cerrarConteo_falla_siNoEstaRevisado() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));

        assertThatThrownBy(() -> service.cerrarConteo(countId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revisado");
    }

    @Test
    void markReviewed_falla_siSigueEnBorrador() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));

        assertThatThrownBy(() -> service.markReviewed(countId, "Notas"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("terminar");
    }

    @Test
    void buildReport_agrupaEmpaquesEnCategoriaPropia() throws Exception {
        Long packagingProductId = 31L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(packagingProductId).code("SUM-01").name("Bolsa").categoryId(categoryId).build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(packagingProductId)
                        .productCode("SUM-01")
                        .productName("Bolsa")
                        .entradas(20)
                        .build()
        ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getCategories()).hasSize(1);
        assertThat(report.getCategories().get(0).getCategoryName()).isEqualTo("Empaques");
        assertThat(report.getCategories().get(0).getRows().get(0).isPackaging()).isTrue();
    }

    @Test
    void salidaDevolucionForDiff_empaqueNoRestaDevolucionEnDiferencia() {
        assertThat(KioscoInventoryCountService.salidaDevolucionForDiff(true, 15)).isZero();
        assertThat(KioscoInventoryCountService.salidaDevolucionForDiff(false, 15)).isEqualTo(15);
    }

    @Test
    void computeDiferenciaConteo_empaqueIgnoraSalidaDevolucion() {
        int total = 25;
        int inventarioFinal = 10;
        int salidaDevolucion = KioscoInventoryCountService.salidaDevolucionForDiff(true, 15);
        assertThat(KioscoInventoryCountService.computeDiferenciaConteo(total, inventarioFinal, salidaDevolucion))
                .isEqualTo(15);
    }

    @Test
    void buildReport_separaBilleterasPorPublico() throws Exception {
        Long walletCategoryId = 80L;
        Long damaProductId = 32L;
        Long cabProductId = 33L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(damaProductId).code("B-1").name("Billetera A").categoryId(walletCategoryId)
                        .audienceCategory("DAMA").build(),
                ProductEntity.builder().id(cabProductId).code("B-2").name("Billetera B").categoryId(walletCategoryId)
                        .audienceCategory("CABALLERO").build()
        ));
        when(productCategoryRepository.findAllById(any())).thenReturn(List.of(
                ProductCategoryEntity.builder().id(walletCategoryId).code("BILL").name("Billeteras").build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(damaProductId).productCode("B-1").productName("Billetera A")
                        .colorId(colorId).colorName("Negro").entradas(5).build(),
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(cabProductId).productCode("B-2").productName("Billetera B")
                        .colorId(colorId + 1).colorName("Cafe").entradas(3).build()
        ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getCategories()).hasSize(2);
        assertThat(report.getCategories().stream().map(c -> c.getCategoryName()))
                .containsExactlyInAnyOrder("Billeteras — Dama", "Billeteras — Caballero");
    }

    @Test
    void buildReport_expandeCinchosPorTallaYColor() throws Exception {
        Long fossProductId = 34L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(fossProductId).code("FOSS-100").name("Cincho casual")
                        .categoryId(categoryId).cinchoType("CASUAL").build()
        ));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(501L)
                        .locationId(locationId)
                        .productId(fossProductId)
                        .colorId(colorId)
                        .sizesData("{\"28\":2,\"30\":3}")
                        .build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(fossProductId).productCode("FOSS-100").productName("Cincho casual")
                        .colorId(colorId).colorName("Negro").entradas(5).build()
        ));
        when(kioscoInventoryService.buildKardexByStockAndSize(eq(locationId), eq(from), eq(to), eq(countId)))
                .thenReturn(Map.of(
                        501L,
                        Map.of(
                                "28", KioscoInventoryService.SizeKardexBucket.of(0, 0, 2, 0, 0, 0),
                                "30", KioscoInventoryService.SizeKardexBucket.of(0, 0, 3, 0, 0, 0)
                        )
                ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        var rows = report.getCategories().get(0).getRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> r.getSizeLabel())).containsExactly("28", "30");
        assertThat(rows.get(0).getInventarioInicial()).isZero();
        assertThat(rows.get(0).getInventarioFinal()).isEqualTo(2);
        assertThat(rows.get(1).getInventarioInicial()).isZero();
        assertThat(rows.get(1).getInventarioFinal()).isEqualTo(3);
        assertThat(rows.get(0).getInventarioInicial() + rows.get(0).getEntradas()).isEqualTo(rows.get(0).getInventarioFinal());
        assertThat(report.getTotalGeneral().getInventarioFinal()).isEqualTo(5);
    }

    @Test
    void buildReport_fusionaKardexPorTallaDeNuevoYViejo() throws Exception {
        // NUEVO vacío aparece primero; movimientos reales están en VIEJO (caso FOSS-38 sobrante falso).
        Long fossProductId = 38L;
        long stockNuevo = 19079L;
        long stockViejo = 7819L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(fossProductId).code("FOSS-38").name("CINCHO SILVANA")
                        .categoryId(categoryId).cinchoType("CASUAL").build()
        ));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockNuevo)
                        .locationId(locationId)
                        .productId(fossProductId)
                        .colorId(colorId)
                        .hardwareCondition("NUEVO")
                        .currentStock(0)
                        .sizesData("{}")
                        .build(),
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockViejo)
                        .locationId(locationId)
                        .productId(fossProductId)
                        .colorId(colorId)
                        .hardwareCondition("VIEJO")
                        .currentStock(6)
                        .sizesData("{\"34\":1,\"36\":2,\"38\":3}")
                        .build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(fossProductId).productCode("FOSS-38").productName("CINCHO SILVANA")
                        .colorId(colorId).colorName("CAFE").entradas(6).build()
        ));
        when(kioscoInventoryService.buildKardexByStockAndSize(eq(locationId), eq(from), eq(to), eq(countId)))
                .thenReturn(Map.of(
                        stockViejo,
                        Map.of(
                                "34", KioscoInventoryService.SizeKardexBucket.of(0, 0, 1, 0, 0, 0),
                                "36", KioscoInventoryService.SizeKardexBucket.of(0, 0, 2, 0, 0, 0),
                                "38", KioscoInventoryService.SizeKardexBucket.of(0, 0, 3, 0, 0, 0)
                        )
                ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        var rows = report.getCategories().get(0).getRows();
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r -> r.getSizeLabel())).containsExactly("34", "36", "38");
        assertThat(rows.stream().map(r -> r.getEntradas())).containsExactly(1, 2, 3);
        assertThat(rows.stream().map(r -> r.getInventarioFinal())).containsExactly(1, 2, 3);
        assertThat(report.getTotalGeneral().getInventarioFinal()).isEqualTo(6);
    }

    @Test
    void buildReport_kardexPorTallaCuadraIniMasMovimientosConFin() throws Exception {
        Long fossProductId = 34L;
        long stockId = 501L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(fossProductId).code("FOSS-5").name("Cincho Giorgio")
                        .categoryId(categoryId).cinchoType("CASUAL").build()
        ));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockId)
                        .locationId(locationId)
                        .productId(fossProductId)
                        .colorId(colorId)
                        .sizesData("{\"34\":0}")
                        .build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(fossProductId).productCode("FOSS-5").productName("Cincho Giorgio")
                        .colorId(colorId).colorName("Cafe").entradas(1).build()
        ));
        when(kioscoInventoryService.buildKardexByStockAndSize(eq(locationId), eq(from), eq(to), eq(countId)))
                .thenReturn(Map.of(
                        stockId,
                        Map.of("34", KioscoInventoryService.SizeKardexBucket.of(0, 0, 1, 0, 0, 0))
                ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        var row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getSizeLabel()).isEqualTo("34");
        assertThat(row.getInventarioInicial()).isZero();
        assertThat(row.getEntradas()).isEqualTo(1);
        assertThat(row.getInventarioFinal()).isEqualTo(1);
        assertThat(row.getInventarioInicial() + row.getEntradas()).isEqualTo(row.getInventarioFinal());
    }

    @Test
    void getSubcountReport_devuelveTipoSubconteoConInventarioAlCorte() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        stubSubcountKardex(asOf, List.of(kardexRow(7)));

        KioscoPhysicalCountReportResponse report = service.getSubcountReport(countId, asOf);

        assertThat(report.getReportType()).isEqualTo("SUBCONTEO");
        assertThat(report.getAsOfDate()).isEqualTo(asOf);
        assertThat(report.getParentCountId()).isEqualTo(countId);
        assertThat(report.getCategories().get(0).getRows().get(0).getInventarioFinal()).isEqualTo(7);
    }

    @Test
    void buildReport_subtotalNetDifferenceBalancesPositiveAndNegativeRows() throws Exception {
        Long secondProductId = 31L;
        Long secondColorId = 41L;
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(productId).code("P-1").name("Tarjetero").categoryId(categoryId).build(),
                ProductEntity.builder().id(secondProductId).code("P-2").name("Tarjetero B").categoryId(categoryId).build()
        ));
        stubPrincipalKardex(List.of(
                kardexRow(10),
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(secondProductId)
                        .productCode("P-2")
                        .productName("Tarjetero B")
                        .colorId(secondColorId)
                        .colorName("Negro")
                        .entradas(10)
                        .build()
        ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });
        when(itemRepository.findByCountId(countId)).thenReturn(List.of(
                KioscoPhysicalCountItemEntity.builder()
                        .id(901L)
                        .countId(countId)
                        .productId(productId)
                        .colorId(colorId)
                        .countsData("{\"V1\":12}")
                        .build(),
                KioscoPhysicalCountItemEntity.builder()
                        .id(902L)
                        .countId(countId)
                        .productId(secondProductId)
                        .colorId(secondColorId)
                        .countsData("{\"V1\":8}")
                        .build()
        ));

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getCategories()).hasSize(1);
        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow subtotal =
                report.getCategories().get(0).getSubtotal();
        assertThat(subtotal.getTotal()).isEqualTo(20);
        assertThat(subtotal.getInventarioFinal()).isEqualTo(20);
        assertThat(subtotal.getDiferencia()).isZero();
        assertThat(report.getTotalGeneral().getDiferencia()).isZero();
    }

    @Test
    void getSubcountReport_falla_siFechaFueraDelPeriodo() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));

        assertThatThrownBy(() -> service.getSubcountReport(countId, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fecha de corte");
    }

    @Test
    void upsertItems_falla_siConteoEstaContado() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .status(KioscoPhysicalCountStatus.CONTADO)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .counts(java.util.Map.of("V1", 2))
                .build();

        assertThatThrownBy(() -> service.upsertItems(countId, List.of(request)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bloqueadas");
    }

    @Test
    void upsertItems_falla_siConteoEstaCerrado() {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .status(KioscoPhysicalCountStatus.CERRADO)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        KioscoPhysicalCountItemUpsertRequest request = KioscoPhysicalCountItemUpsertRequest.builder()
                .productId(productId)
                .counts(java.util.Map.of("V1", 2))
                .build();

        assertThatThrownBy(() -> service.upsertItems(countId, List.of(request)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cerrado");
    }

    @Test
    void buildReport_primerConteo_entradaPreviaVaAInicialNoAEntradas() throws Exception {
        long stockId = 913L;
        LocalDateTime periodStart = from.atStartOfDay();
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockId)
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(productId).productCode("BD-8").productName("Bolso Perla")
                        .colorId(colorId).colorName("Salmon Acabado Flores")
                        .ventas(1)
                        .build()
        ));
        when(kioscoInventoryService.computeStockBalanceByStockId(eq(locationId), eq(periodStart)))
                .thenReturn(Map.of(stockId, 1));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        var row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getInventarioInicial()).isEqualTo(1);
        assertThat(row.getEntradas()).isZero();
        assertThat(row.getVentas()).isEqualTo(1);
        assertThat(row.getInventarioFinal()).isZero();
    }

    @Test
    void buildReport_segundoConteo_iniEsFinDelConteoCerrado_gapVaAEntradas() throws Exception {
        long stockId = 913L;
        long previousCountId = 100L;
        LocalDate previousFrom = LocalDate.of(2026, 5, 1);
        LocalDate previousTo = LocalDate.of(2026, 5, 31);
        LocalDateTime openingCutoff = previousTo.plusDays(1).atStartOfDay();

        KioscoPhysicalCountEntity previousClosed = KioscoPhysicalCountEntity.builder()
                .id(previousCountId)
                .locationId(locationId)
                .periodFrom(previousFrom)
                .periodTo(previousTo)
                .status(KioscoPhysicalCountStatus.CERRADO)
                .build();

        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                eq(locationId), eq(KioscoPhysicalCountStatus.CERRADO), eq(from), eq(countId)))
                .thenReturn(Optional.of(previousClosed));
        // Al reconstruir el conteo cerrado previo no hay otro CERRADO anterior.
        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                eq(locationId), eq(KioscoPhysicalCountStatus.CERRADO), eq(previousFrom), eq(previousCountId)))
                .thenReturn(Optional.empty());

        // Fin. del conteo cerrado = Ini 0 + Ent 1 = 1 (aunque el ledger floored diga otra cosa).
        when(kioscoInventoryService.buildKardexRows(
                eq(locationId), eq(previousFrom), eq(previousTo), eq(true), eq(previousTo), eq(previousCountId)))
                .thenReturn(List.of(
                        KioscoKardexReportResponse.KioscoKardexRow.builder()
                                .productId(productId).productCode("BD-8").productName("Bolso Perla")
                                .colorId(colorId).colorName("Salmon Acabado Flores")
                                .entradas(1)
                                .build()
                ));
        when(kioscoInventoryService.buildKardexByStockAndSize(
                eq(locationId), eq(previousFrom), eq(previousTo), eq(previousCountId)))
                .thenReturn(Map.of());

        when(kioscoInventoryService.computePrePeriodEntradasByStockId(eq(locationId), eq(openingCutoff), any(LocalDateTime.class)))
                .thenReturn(Map.of(stockId, 1));
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockId)
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .build()
        ));
        stubPrincipalKardex(List.of(
                KioscoKardexReportResponse.KioscoKardexRow.builder()
                        .productId(productId).productCode("BD-8").productName("Bolso Perla")
                        .colorId(colorId).colorName("Salmon Acabado Flores")
                        .build()
        ));
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        var row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getInventarioInicial()).isEqualTo(1);
        assertThat(row.getEntradas()).isEqualTo(1);
        assertThat(row.getInventarioFinal()).isEqualTo(2);
    }

    @Test
    void buildReport_segundoConteo_iniUsaFinCeroDelCerrado_aunqueLedgerDigaUno() throws Exception {
        long stockId = 7574L;
        long previousCountId = 100L;
        LocalDate previousFrom = LocalDate.of(2026, 7, 28);
        LocalDate previousTo = LocalDate.of(2026, 8, 3);
        LocalDate currentFrom = LocalDate.of(2026, 8, 4);
        LocalDate currentTo = LocalDate.of(2026, 8, 10);
        LocalDateTime openingCutoff = previousTo.plusDays(1).atStartOfDay();

        KioscoPhysicalCountEntity previousClosed = KioscoPhysicalCountEntity.builder()
                .id(previousCountId)
                .locationId(locationId)
                .periodFrom(previousFrom)
                .periodTo(previousTo)
                .status(KioscoPhysicalCountStatus.CERRADO)
                .build();

        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, currentFrom, currentTo))
                .thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });
        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                eq(locationId), eq(KioscoPhysicalCountStatus.CERRADO), eq(currentFrom), eq(countId)))
                .thenReturn(Optional.of(previousClosed));
        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                eq(locationId), eq(KioscoPhysicalCountStatus.CERRADO), eq(previousFrom), eq(previousCountId)))
                .thenReturn(Optional.empty());

        // Conteo cerrado: Ini 0 + Ent 1 - Vtas 2 + AV 1 = Fin 0 (caso B-22 CAFE).
        when(kioscoInventoryService.buildKardexRows(
                eq(locationId), eq(previousFrom), eq(previousTo), eq(true), eq(previousTo), eq(previousCountId)))
                .thenReturn(List.of(
                        KioscoKardexReportResponse.KioscoKardexRow.builder()
                                .productId(productId).productCode("B-22").productName("BILLETERA EDWARD")
                                .colorId(colorId).colorName("CAFE")
                                .entradas(1)
                                .ventas(2)
                                .anulacionVenta(1)
                                .build()
                ));
        when(kioscoInventoryService.buildKardexByStockAndSize(
                eq(locationId), eq(previousFrom), eq(previousTo), eq(previousCountId)))
                .thenReturn(Map.of());

        // Ledger floored incorrecto / divergente: diría Ini=1 si se usara.
        when(kioscoInventoryService.computeStockBalanceByStockId(eq(locationId), eq(openingCutoff)))
                .thenReturn(Map.of(stockId, 1));
        when(kioscoInventoryService.computePrePeriodEntradasByStockId(eq(locationId), eq(openingCutoff), any(LocalDateTime.class)))
                .thenReturn(Map.of());

        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockId)
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .build()
        ));
        when(kioscoInventoryService.buildKardexRows(
                eq(locationId), eq(currentFrom), eq(currentTo), eq(true), eq(currentTo), eq(countId)))
                .thenReturn(List.of(
                        KioscoKardexReportResponse.KioscoKardexRow.builder()
                                .productId(productId).productCode("B-22").productName("BILLETERA EDWARD")
                                .colorId(colorId).colorName("CAFE")
                                .build()
                ));
        when(kioscoInventoryService.buildKardexByStockAndSize(
                eq(locationId), eq(currentFrom), eq(currentTo), eq(countId)))
                .thenReturn(Map.of());

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, currentFrom, currentTo);

        var row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getInventarioInicial()).isZero();
        assertThat(row.getInventarioFinal()).isZero();
        // No debe usarse el saldo floored del ledger (=1).
        org.mockito.Mockito.verify(kioscoInventoryService, org.mockito.Mockito.never())
                .computeStockBalanceByStockId(eq(locationId), eq(openingCutoff));
    }

    @Test
    void extractClosingBalances_incluyeFinCeroParaHeredarIni() {
        KioscoPhysicalCountReportResponse report = KioscoPhysicalCountReportResponse.builder()
                .categories(List.of(
                        KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup.builder()
                                .categoryName("Billeteras")
                                .rows(List.of(
                                        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                                                .productId(productId)
                                                .colorId(colorId)
                                                .inventarioFinal(0)
                                                .build(),
                                        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                                                .productId(productId + 1)
                                                .colorId(colorId)
                                                .inventarioFinal(5)
                                                .build()
                                ))
                                .build()
                ))
                .build();

        var closing = KioscoInventoryCountService.extractClosingBalances(report);

        assertThat(closing.byProductColor).containsEntry(productId + ":" + colorId, 0);
        assertThat(closing.byProductColor).containsEntry((productId + 1) + ":" + colorId, 5);
        assertThat(KioscoInventoryCountService.resolveOpeningInventarioInicial(
                productId + ":" + colorId, closing, 1)).isZero();
        assertThat(KioscoInventoryCountService.resolveOpeningInventarioInicial(
                (productId + 1) + ":" + colorId, closing, 99)).isEqualTo(5);
        assertThat(KioscoInventoryCountService.resolveOpeningInventarioInicial(
                "999:1", closing, 7)).isZero();
    }

    @Test
    void buildReport_periodosContiguosMismoDia_iniHeredaFinCeroDelSnapshot() throws Exception {
        long stockId = 8068L;
        long previousCountId = 100L;
        LocalDate previousFrom = LocalDate.of(2026, 7, 28);
        LocalDate previousTo = LocalDate.of(2026, 8, 3);
        LocalDate currentFrom = LocalDate.of(2026, 8, 3);
        LocalDate currentTo = LocalDate.of(2026, 8, 9);
        LocalDate effectiveKardexFrom = previousTo.plusDays(1); // 2026-08-04

        String snapshot = service.serializeClosingBalances(
                new KioscoInventoryCountService.PreviousClosingBalances(
                        Map.of(productId + ":" + colorId, 0),
                        Map.of(productId + ":" + colorId, Map.of("34", 0))
                ));

        KioscoPhysicalCountEntity previousClosed = KioscoPhysicalCountEntity.builder()
                .id(previousCountId)
                .locationId(locationId)
                .periodFrom(previousFrom)
                .periodTo(previousTo)
                .status(KioscoPhysicalCountStatus.CERRADO)
                .closingBalancesData(snapshot)
                .build();

        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, currentFrom, currentTo))
                .thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });
        when(countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                eq(locationId), eq(KioscoPhysicalCountStatus.CERRADO), eq(currentFrom), eq(countId)))
                .thenReturn(Optional.of(previousClosed));

        when(kioscoInventoryService.buildKardexRows(
                eq(locationId), eq(effectiveKardexFrom), eq(currentTo), eq(true), eq(currentTo), eq(countId)))
                .thenReturn(List.of(
                        KioscoKardexReportResponse.KioscoKardexRow.builder()
                                .productId(productId).productCode("FOSS-33").productName("CINCHO DALILA")
                                .colorId(colorId).colorName("NEGRO/CAFE")
                                .build()
                ));
        when(kioscoInventoryService.buildKardexByStockAndSize(
                eq(locationId), eq(effectiveKardexFrom), eq(currentTo), eq(countId)))
                .thenReturn(Map.of());
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(any())).thenReturn(List.of(
                com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity.builder()
                        .id(stockId)
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .currentStock(1)
                        .sizesData("{\"34\":1}")
                        .build()
        ));
        when(productRepository.findAllById(any())).thenReturn(List.of(ProductEntity.builder()
                .id(productId).code("FOSS-33").name("CINCHO DALILA").categoryId(categoryId)
                .cinchoType("REVERSIBLE").build()));

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, currentFrom, currentTo);

        var row = report.getCategories().get(0).getRows().get(0);
        assertThat(row.getInventarioInicial()).isZero();
        assertThat(row.getVentas()).isZero();
        assertThat(row.getInventarioFinal()).isZero();
        // Snapshot evita caer al ledger (stock vivo = 1).
        verify(kioscoInventoryService, never()).computeStockBalanceByStockId(any(), any());
        // No reconstruye el reporte del conteo cerrado si hay snapshot.
        verify(kioscoInventoryService, never()).buildKardexRows(
                eq(locationId), eq(previousFrom), eq(previousTo), eq(true), eq(previousTo), eq(previousCountId));
    }

    @Test
    void serializeAndDeserializeClosingBalances_roundTrip() {
        var original = new KioscoInventoryCountService.PreviousClosingBalances(
                Map.of(productId + ":" + colorId, 0, (productId + 1) + ":" + colorId, 5),
                Map.of(productId + ":" + colorId, Map.of("34", 0))
        );
        String json = service.serializeClosingBalances(original);
        var restored = service.deserializeClosingBalances(json);

        assertThat(restored).isNotNull();
        assertThat(restored.byProductColor).containsEntry(productId + ":" + colorId, 0);
        assertThat(restored.byProductColor).containsEntry((productId + 1) + ":" + colorId, 5);
        assertThat(restored.byProductColorAndSize.get(productId + ":" + colorId)).containsEntry("34", 0);
    }

    @Test
    void applyUnsizedDeltaToSizeBalances_descuentaFifoDeTallaPositiva() {
        Map<String, Integer> bySize = new LinkedHashMap<>();
        bySize.put("34", 1);

        KioscoInventoryService.applyUnsizedDeltaToSizeBalances(bySize, -1);

        assertThat(bySize.get("34")).isZero();
    }
}
