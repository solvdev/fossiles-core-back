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
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioscoInventoryCountServiceTest {

    @Mock
    private KioscoPhysicalCountRepository countRepository;
    @Mock
    private KioscoPhysicalCountItemRepository itemRepository;
    @Mock
    private KioscoNotificationRecipientRepository notificationRecipientRepository;
    @Mock
    private KioscoInventoryService kioscoInventoryService;
    @Mock
    private KioscoStockRepository kioscoStockRepository;
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
        when(kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(any())).thenReturn(List.of());
    }

    private KioscoKardexReportResponse.KioscoKardexRow kardexRow(int inventarioFinal) {
        return KioscoKardexReportResponse.KioscoKardexRow.builder()
                .productId(productId)
                .productCode("P-1")
                .productName("Tarjetero")
                .colorId(colorId)
                .colorName("Cafe")
                .inventarioInicial(5)
                .entradas(5)
                .inventarioFinal(inventarioFinal)
                .build();
    }

    @Test
    void startOrGetSession_creaNuevaSesion_siNoExiste() throws Exception {
        when(countRepository.findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)).thenReturn(Optional.empty());
        when(countRepository.save(any(KioscoPhysicalCountEntity.class))).thenAnswer(inv -> {
            KioscoPhysicalCountEntity entity = inv.getArgument(0);
            entity.setId(countId);
            return entity;
        });
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.startOrGetSession(locationId, from, to);

        assertThat(report.getId()).isEqualTo(countId);
        assertThat(report.getStatus()).isEqualTo("DRAFT");
        assertThat(report.getGeneratedByName()).isEqualTo("paola");
        assertThat(report.getCategories()).hasSize(1);
        assertThat(report.getCategories().get(0).getCategoryName()).isEqualTo("Tarjeteros");
        assertThat(report.getCategories().get(0).getRows()).hasSize(1);
        assertThat(report.getTotalGeneral().getInventarioFinal()).isEqualTo(10);
        assertThat(report.getTotalGeneral().getDiferencia()).isEqualTo(10);
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
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

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
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

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
        assertThat(row.getDiferencia()).isEqualTo(6);
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
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

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
        assertThat(savedItemRef.get().getSizeLocationCountsData()).contains("\"E\"");
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
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

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
    void markReviewed_actualizaEstadoRevisorYNotas() throws Exception {
        KioscoPhysicalCountEntity count = KioscoPhysicalCountEntity.builder()
                .id(countId)
                .locationId(locationId)
                .periodFrom(from)
                .periodTo(to)
                .status(KioscoPhysicalCountStatus.DRAFT)
                .generatedBy(userId)
                .build();
        when(countRepository.findById(countId)).thenReturn(Optional.of(count));
        when(securityUtil.getCurrentUserId()).thenReturn(reviewerId);
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

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
        when(kioscoInventoryService.buildKardexRows(locationId, from, to, false)).thenReturn(List.of(kardexRow(10)));

        KioscoPhysicalCountReportResponse report = service.cerrarConteo(countId);

        assertThat(report.getStatus()).isEqualTo("CERRADO");
        assertThat(report.getClosedByName()).isEqualTo("gustavo");
        assertThat(count.getClosedAt()).isNotNull();
        assertThat(count.getClosedBy()).isEqualTo(reviewerId);
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
}
