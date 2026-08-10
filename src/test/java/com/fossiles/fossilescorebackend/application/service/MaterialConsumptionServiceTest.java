package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialConsumptionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialConsumptionServiceTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private BomRepository bomRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private InventoryService inventoryService;
    @Mock private MaterialRepository materialRepository;
    @Mock private ProductRepository productRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private SecurityUtil securityUtil;

    @InjectMocks
    private MaterialConsumptionService materialConsumptionService;

    @Test
    void hasConsumptionForTaskItem_usesNotesPattern() {
        when(materialConsumptionRepository.existsByProductionOrderIdAndNotesLike(10L, "%item 55 %"))
                .thenReturn(true);

        assertThat(materialConsumptionService.hasConsumptionForTaskItem(10L, 55L)).isTrue();
        verify(materialConsumptionRepository).existsByProductionOrderIdAndNotesLike(eq(10L), eq("%item 55 %"));
    }

    @Test
    void hasConsumptionForTaskItem_nullArgs_falseWithoutQuery() {
        assertThat(materialConsumptionService.hasConsumptionForTaskItem(null, 1L)).isFalse();
        assertThat(materialConsumptionService.hasConsumptionForTaskItem(1L, null)).isFalse();
        verifyNoInteractions(materialConsumptionRepository);
    }

    @Test
    void shouldConsumeOnItemMaterialsDelivery_skipsWhenOrderAlreadyConsumed() {
        assertThat(materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(true, 10L, 55L)).isFalse();
        verifyNoInteractions(materialConsumptionRepository);
    }

    @Test
    void shouldConsumeOnItemMaterialsDelivery_skipsWhenItemAlreadyConsumed() {
        when(materialConsumptionRepository.existsByProductionOrderIdAndNotesLike(10L, "%item 55 %"))
                .thenReturn(true);

        assertThat(materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(false, 10L, 55L)).isFalse();
    }

    @Test
    void shouldConsumeOnItemMaterialsDelivery_allowsWhenNeitherConsumed() {
        when(materialConsumptionRepository.existsByProductionOrderIdAndNotesLike(10L, "%item 55 %"))
                .thenReturn(false);

        assertThat(materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(false, 10L, 55L)).isTrue();
        assertThat(materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(null, 10L, 55L)).isTrue();
    }
}
