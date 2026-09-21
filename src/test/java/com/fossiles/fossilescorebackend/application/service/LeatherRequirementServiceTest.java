package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
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

    private LeatherRequirementService service;

    @BeforeEach
    void setUp() {
        service = new LeatherRequirementService(
                variantLeatherRepository, leatherInventoryRepository, taskRepository, taskItemRepository);
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
}
