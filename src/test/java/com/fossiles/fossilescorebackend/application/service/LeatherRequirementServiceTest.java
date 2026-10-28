package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeatherRequirementServiceTest {

    @Mock private ProductVariantLeatherRepository variantLeatherRepository;
    @Mock private LeatherInventoryRepository leatherInventoryRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private MaterialRepository materialRepository;

    private LeatherRequirementService service;

    @BeforeEach
    void setUp() {
        service = new LeatherRequirementService(
                variantLeatherRepository, leatherInventoryRepository, taskRepository, taskItemRepository,
                colorRepository, materialRepository);
    }

    @Test
    void blocksWhenNoRecipe() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, null, 2);
        assertThat(need.blocked()).isTrue();
        assertThat(need.reason()).contains("product_variant_leather");
        assertThat(service.canCover(need, Map.of())).isFalse();
    }

    @Test
    void blocksWhenNotEnoughFt2() {
        ProductEntity product = ProductEntity.builder().id(9L).code("P-1").build();
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.of(
                ProductVariantLeatherEntity.builder()
                        .productId(9L)
                        .leatherMaterialId(4L)
                        .qtyPerUnit(new BigDecimal("2.0"))
                        .build()));
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
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.of(
                ProductVariantLeatherEntity.builder()
                        .productId(9L)
                        .leatherMaterialId(4L)
                        .qtyPerUnit(new BigDecimal("1.0"))
                        .build()));
        when(leatherInventoryRepository.findByMaterialId(4L)).thenReturn(Optional.of(
                LeatherInventoryEntity.builder().materialId(4L).quantityAvailable(new BigDecimal("3.0")).build()));

        LeatherRequirementService.LeatherNeed two = service.resolveNeed(product, null, 2);
        Map<Long, BigDecimal> reserved = new HashMap<>();
        assertThat(service.canCover(two, reserved)).isTrue();
        reserved.merge(two.materialId(), two.qtyFt2(), BigDecimal::add);

        LeatherRequirementService.LeatherNeed oneMore = service.resolveNeed(product, null, 2);
        assertThat(service.canCover(oneMore, reserved)).isFalse();
    }

    private static ProductVariantLeatherEntity recipe(long colorId, long materialId, String qty) {
        return ProductVariantLeatherEntity.builder().productId(9L).colorId(colorId)
                .leatherMaterialId(materialId).qtyPerUnit(new BigDecimal(qty)).build();
    }

    private static MaterialEntity material(long id, String name, Long materialColorId) {
        return MaterialEntity.builder().id(id).name(name).status("active").materialColorId(materialColorId).build();
    }

    @Test
    void derivesMissingColorFromProductQtyAndLeatherOfThatColor() {
        ProductEntity product = ProductEntity.builder().id(9L).code("BD-25").build();
        when(variantLeatherRepository.findByProductIdAndColorId(9L, 3L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdOrderByColorIdAsc(9L))
                .thenReturn(List.of(recipe(2L, 81L, "5.0")));
        when(colorRepository.findById(3L)).thenReturn(Optional.of(ColorEntity.builder().id(3L).name("NEGRO").build()));
        when(materialRepository.findByNameIgnoreCase("CUERO NEGRO LISO"))
                .thenReturn(List.of(material(80L, "CUERO NEGRO LISO", 4L)));
        when(materialRepository.findAllById(List.of(81L)))
                .thenReturn(List.of(material(81L, "CUERO CAFE LISO", 5L)));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 3L, 2);

        assertThat(need.blocked()).isFalse();
        assertThat(need.materialId()).isEqualTo(80L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("10.000");
    }

    @Test
    void derivedCafeKeepsReversibleWhenProductAlreadyUsesIt() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-1").build();
        when(variantLeatherRepository.findByProductIdAndColorId(9L, 2L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdOrderByColorIdAsc(9L))
                .thenReturn(List.of(recipe(3L, 74L, "2.25"), recipe(4L, 82L, "2.25"), recipe(1L, 83L, "2.25")));
        when(colorRepository.findById(2L)).thenReturn(Optional.of(ColorEntity.builder().id(2L).name("CAFE").build()));
        when(materialRepository.findByNameIgnoreCase("CUERO CAFE LISO"))
                .thenReturn(List.of(material(81L, "CUERO CAFE LISO", 5L)));
        when(materialRepository.findAllById(List.of(74L)))
                .thenReturn(List.of(material(74L, "CUERO DE CINCHO REVERSIBLE", 4L)));
        when(materialRepository.findByNameIgnoreCaseAndMaterialColorId("CUERO DE CINCHO REVERSIBLE", 5L))
                .thenReturn(List.of(material(75L, "CUERO DE CINCHO REVERSIBLE", 5L)));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 2L, 1);

        assertThat(need.materialId()).isEqualTo(75L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("2.250");
    }

    @Test
    void derivedFloresWithoutFloresLeatherFallsBackToPlainLeatherOfBaseColor() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-51").build();
        when(variantLeatherRepository.findByProductIdAndColorId(9L, 42L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdOrderByColorIdAsc(9L))
                .thenReturn(List.of(recipe(1L, 83L, "2.5"), recipe(4L, 82L, "2.5")));
        when(colorRepository.findById(42L))
                .thenReturn(Optional.of(ColorEntity.builder().id(42L).name("WHISKY ACABADO FLORES").build()));
        when(materialRepository.findByNameIgnoreCase("CUERO WHISKY FLORES")).thenReturn(List.of());
        when(materialRepository.findByNameIgnoreCase("CUERO WHISKY LISO"))
                .thenReturn(List.of(material(83L, "CUERO WHISKY LISO", 10L)));

        LeatherRequirementService.LeatherNeed need = service.resolveNeed(product, 42L, 4);

        assertThat(need.materialId()).isEqualTo(83L);
        assertThat(need.qtyFt2()).isEqualByComparingTo("10.000");
    }

    @Test
    void combinedColorsStayWithoutRecipe() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-41").build();
        when(variantLeatherRepository.findByProductIdAndColorId(9L, 45L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdOrderByColorIdAsc(9L))
                .thenReturn(List.of(recipe(1L, 83L, "1.75")));
        when(colorRepository.findById(45L))
                .thenReturn(Optional.of(ColorEntity.builder().id(45L).name("NEGRO/WHISKY").build()));

        assertThat(service.resolveNeed(product, 45L, 1).blocked()).isTrue();
    }

    @Test
    void productWithoutAnyRecipeStaysBlocked() {
        ProductEntity product = ProductEntity.builder().id(9L).code("B-7-D").build();
        when(variantLeatherRepository.findByProductIdAndColorId(9L, 3L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdAndColorIdIsNull(9L)).thenReturn(Optional.empty());
        when(variantLeatherRepository.findByProductIdOrderByColorIdAsc(9L)).thenReturn(List.of());

        assertThat(service.resolveNeed(product, 3L, 1).blocked()).isTrue();
    }
}
