package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.MaterialsTaskViewResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemMaterialPickRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MaterialsTaskViewServiceTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BomRepository bomRepository;
    @Mock private BomItemRepository bomItemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private TaskItemMaterialPickRepository taskItemMaterialPickRepository;

    private MaterialsTaskViewService service;

    @BeforeEach
    void setUp() {
        service = new MaterialsTaskViewService(taskRepository, taskItemRepository, productionOrderRepository,
                productionOrderItemRepository, productRepository, bomRepository, bomItemRepository,
                materialRepository, taskItemMaterialPickRepository);
    }

    private TaskEntity completedTask(long id, int daysAgo, boolean materialsDelivered) {
        TaskEntity task = TaskEntity.builder()
                .id(id).code("TK-" + id).status("COMPLETED").productionOrderId(500L).productionOrderCode("OPL-1")
                .completedAt(GuatemalaDateTime.now().minusDays(daysAgo))
                .scheduledDate(GuatemalaDateTime.today().minusDays(daysAgo))
                .leatherDelivered(true).dieCutReady(true).materialsDelivered(materialsDelivered)
                .build();
        lenient().when(taskItemRepository.findByTaskIdIn(List.of(id))).thenReturn(List.of(
                TaskItemEntity.builder().id(id * 10).taskId(id).productId(7L).quantity(1)
                        .materialsDelivered(materialsDelivered).build()));
        return task;
    }

    @Test
    void completedTaskWithoutMaterialsStillShowsAndCanBeDelivered() {
        TaskEntity task = completedTask(1L, 1, false);

        List<MaterialsTaskViewResponse> views = service.build(List.of(task), true);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getTaskCode()).isEqualTo("TK-1");
        assertThat(views.get(0).getCanDeliverMaterials()).isTrue();
        assertThat(views.get(0).getWorkflowStatus()).isEqualTo("PENDING_MATERIAL_DELIVERY");
    }

    @Test
    void completedTaskStillShowsWhenItsOrderIsAlreadyCompleted() {
        TaskEntity task = completedTask(1L, 1, false);
        lenient().when(productionOrderRepository.findAllById(List.of(500L))).thenReturn(List.of(
                ProductionOrderEntity.builder().id(500L).code("OPL-1").status("COMPLETED").build()));

        assertThat(service.build(List.of(task), true)).hasSize(1);
    }

    @Test
    void completedTaskWithMaterialsDeliveredDoesNotShow() {
        assertThat(service.build(List.of(completedTask(1L, 1, true)), true)).isEmpty();
    }

    @Test
    void completedTaskOlderThanTheWindowDoesNotShow() {
        TaskEntity old = completedTask(1L, MaterialsTaskViewService.COMPLETED_PENDING_MATERIALS_DAYS + 3, false);

        assertThat(service.build(List.of(old), true)).isEmpty();
    }

    @Test
    void cancelledTaskNeverShows() {
        TaskEntity task = completedTask(1L, 0, false);
        task.setStatus("CANCELLED");

        assertThat(service.build(List.of(task), true)).isEmpty();
    }
}
