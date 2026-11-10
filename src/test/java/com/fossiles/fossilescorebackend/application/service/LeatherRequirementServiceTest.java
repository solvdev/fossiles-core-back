package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.support.SimpleCacheManager;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Receta de cuero por (producto, color). Las recetas, colores y materiales se leen juntos en
 * {@link LeatherRecipeCatalog}; los casos y resultados son los mismos que cuando cada combinación
 * consultaba la base por separado.
 */
@ExtendWith(MockitoExtension.class)
class LeatherRequirementServiceTest {

    @Mock private ProductVariantLeatherRepository variantLeatherRepository;
    @Mock private LeatherInventoryRepository leatherInventoryRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private MaterialRepository materialRepository;

    private LeatherRecipeCatalog catalog;
    private LeatherRequirementService service;

    @BeforeEach
    void setUp() {
        // Fuera de Spring el SimpleCacheManager no se inicializa solo.
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        catalog = new LeatherRecipeCatalog(variantLeatherRepository, colorRepository, materialRepository, manager);
        service = new LeatherRequirementService(catalog, leatherInventoryRepository, taskRepository, taskItemRepository);
    }

    private void recipes(ProductVariantLeatherEntity... rows) {
        when(variantLeatherRepository.findAll()).thenReturn(List.of(rows));
    }

    private void colors(ColorEntity... rows) {
        when(colorRepository.findAll()).thenReturn(List.of(rows));
    }

    private void materials(MaterialEntity... rows) {
        when(materialRepository.findAll()).thenReturn(List.of(rows));
    }

    private static ProductVariantLeatherEntity recipe(long colorId, long materialId, String qty) {
        return ProductVariantLeatherEntity.builder().productId(9L).colorId(colorId)
                .leatherMaterialId(materialId).qtyPerUnit(new BigDecimal(qty)).build();
    }

    private static ProductVariantLeatherEntity genericRecipe(long materialId, String qty) {
        return ProductVariantLeatherEntity.builder().productId(9L)
                .leatherMaterialId(materialId).qtyPerUnit(new BigDecimal(qty)).build();
    }

    private static MaterialEntity material(long id, String name, Long materialColorId) {
        return MaterialEntity.builder().id(id).name(name).status("active").materialColorId(materialColorId).build();
    }

    private static ColorEntity color(long id, String name) {
        return ColorEntity.builder().id(id).name(name).build();
    }

    @Test
    void blocksWhenNoRecipe() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, null, 2);
        assertThat(need.blocked()).isTrue();
        assertThat(need.reason()).contains("product_variant_leather");
        assertThat(service.canCover(need, Map.of())).isFalse();
    }

    @Test
    void blocksWhenNotEnoughFt2() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();
        recipes(genericRecipe(4L, "2.0"));
        when(leatherInventoryRepository.findByMaterialId(4L)).thenReturn(Optional.of(
                LeatherInventoryEntity.builder().materialId(4L).quantityAvailable(new BigDecimal("1.0")).build()));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, null, 2);
        assertThat(need.blocked()).isFalse();
        assertThat(need.qtyFt2()).isEqualByComparingTo("4.000");
        assertThat(service.canCover(need, new HashMap<>())).isFalse();
    }

    @Test
    void coversWhenStockIsEnoughAndReservesInMemory() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();
        recipes(genericRecipe(4L, "1.0"));
        when(leatherInventoryRepository.findByMaterialId(4L)).thenReturn(Optional.of(
                LeatherInventoryEntity.builder().materialId(4L).quantityAvailable(new BigDecimal("3.0")).build()));

        LeatherRequirementService.LeatherNeed two = service.resolveNeed(product, null, 2);
        Map<Long, BigDecimal> reserved = new HashMap<>();
        assertThat(service.canCover(two, reserved)).isTrue();
        reserved.merge(two.materialId(), two.qtyFt2(), BigDecimal::add);

        LeatherRequirementService.LeatherNeed oneMore = service.resolveNeed(product, null, 2);
        assertThat(service.canCover(oneMore, reserved)).isFalse();
    }

    @Test
    void exactColorWinsOverGenericAndGenericOverDerived() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();
        recipes(recipe(3L, 70L, "1.5"), genericRecipe(71L, "2.0"));
        colors(color(2L, "CAFE"));
        materials(material(81L, "CUERO CAFE LISO", 5L));

        LeatherRequirementService.LeatherNeed negro = service.resolvePerUnit(product, 3L);
        assertThat(negro.materialId()).isEqualTo(70L);
        assertThat(negro.qtyFt2()).isEqualByComparingTo("1.5");

        // CAFE no tiene fila propia: usa la genérica, no la derivada con CUERO CAFE LISO.
        LeatherRequirementService.LeatherNeed cafe = service.resolvePerUnit(product, 2L);
        assertThat(cafe.materialId()).isEqualTo(71L);
        assertThat(cafe.qtyFt2()).isEqualByComparingTo("2.0");
    }

    @Test
    void derivesMissingColorFromProductQtyAndLeatherOfThatColor() {
        ProductEntity product = ProductEntity.builder().id(9L).code("BD-25").build();
        recipes(recipe(2L, 81L, "5.0"));
        colors(color(3L, "NEGRO"));
        materials(material(80L, "CUERO NEGRO LISO", 4L), material(81L, "CUERO CAFE LISO", 5L));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 3L, 2);

        assertThat(need.blocked()).isFalse();
        assertThat(need.materialId()).isEqualTo(80L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("10.000");
    }

    @Test
    void derivedCafeKeepsReversibleWhenProductAlreadyUsesIt() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-1").build();
        recipes(recipe(3L, 74L, "2.25"), recipe(4L, 82L, "2.25"), recipe(1L, 83L, "2.25"));
        colors(color(2L, "CAFE"));
        materials(material(81L, "CUERO CAFE LISO", 5L),
                material(74L, "CUERO DE CINCHO REVERSIBLE", 4L),
                material(75L, "CUERO DE CINCHO REVERSIBLE", 5L));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 2L, 1);

        assertThat(need.materialId()).isEqualTo(75L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("2.250");
    }

    @Test
    void derivedFloresWithoutFloresLeatherFallsBackToPlainLeatherOfBaseColor() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-51").build();
        recipes(recipe(1L, 83L, "2.5"), recipe(4L, 82L, "2.5"));
        colors(color(42L, "WHISKY ACABADO FLORES"));
        materials(material(83L, "CUERO WHISKY LISO", 10L));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 42L, 4);

        assertThat(need.materialId()).isEqualTo(83L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("10.000");
    }

    @Test
    void derivedSkipsInactiveLeather() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-51").build();
        recipes(recipe(1L, 83L, "2.5"));
        colors(color(42L, "WHISKY"));
        materials(MaterialEntity.builder().id(83L).name("cuero whisky liso").status("inactive").build());

        assertThat(service.resolveNeed(product, 42L, 1).blocked()).isTrue();
    }

    @Test
    void combinedColorsStayWithoutRecipe() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-41").build();
        recipes(recipe(1L, 83L, "1.75"));
        colors(color(45L, "NEGRO/WHISKY"));

        assertThat(service.resolveNeed(product, 45L, 1).blocked()).isTrue();
    }

    @Test
    void productWithoutAnyRecipeStaysBlocked() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-7-D").build();

        assertThat(service.resolveNeed(product, 3L, 1).blocked()).isTrue();
    }

    @Test
    void recipesAreReadOnceUntilEvicted() {
        ProductEntity product = ProductEntity.builder().id(9L).code("BD-25").build();
        recipes(recipe(2L, 81L, "5.0"));
        colors(color(3L, "NEGRO"), color(4L, "WHISKY"));
        materials(material(80L, "CUERO NEGRO LISO", 4L), material(81L, "CUERO CAFE LISO", 5L));

        for (int i = 0; i < 50; i++) {
            service.resolvePerUnit(product, 2L);
            service.resolvePerUnit(product, 3L);
            service.resolvePerUnit(product, 4L);
            service.resolvePerUnit(product, null);
        }
        verify(variantLeatherRepository, times(1)).findAll();
        verify(colorRepository, times(1)).findAll();
        verify(materialRepository, times(1)).findAll();

        catalog.evict();
        assertThat(service.resolvePerUnit(product, 3L).materialId()).isEqualTo(80L);
        verify(variantLeatherRepository, times(2)).findAll();
    }

    @Test
    void committedFt2LoadsTaskItemsInOneQuery() {
        recipes(recipe(3L, 80L, "2.0"));
        TaskEntity t1 = TaskEntity.builder().id(1L).build();
        TaskEntity t2 = TaskEntity.builder().id(2L).leatherDelivered(true).build();
        TaskEntity t3 = TaskEntity.builder().id(3L).build();
        when(taskRepository.findPendingAndInProgressOrdered()).thenReturn(List.of(t1, t2, t3));
        when(taskItemRepository.findByTaskIdIn(List.of(1L, 3L))).thenReturn(List.of(
                TaskItemEntity.builder().taskId(1L).productId(9L).colorId(3L).quantity(2).build(),
                TaskItemEntity.builder().taskId(3L).productId(9L).colorId(3L).quantity(1).build(),
                TaskItemEntity.builder().taskId(3L).productId(9L).colorId(3L).quantity(5).leatherDelivered(true).build()));

        Map<Long, BigDecimal> committed = service.committedFt2ByMaterial();

        assertThat(committed).containsOnlyKeys(80L);
        assertThat(committed.get(80L)).isEqualByComparingTo("6.0"); // (2 + 1) × 2.0
        verify(taskItemRepository, never()).findByTaskId(anyLong());
        verify(taskItemRepository, times(1)).findByTaskIdIn(anyList());
    }
}
