package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tarea consolidada de OPV de Luis Felipe: la OP base es OPV-5 y la tarea también trabaja la
 * OPV-6. Al terminarla, las dos OP deben pasar a COMPLETED, no solo la cabecera.
 */
class ProductionTaskLifecycleServiceConsolidatedTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskItemRepository taskItemRepository = mock(TaskItemRepository.class);
    private final ProductionOrderRepository productionOrderRepository = mock(ProductionOrderRepository.class);
    private final ProductionOrderWarehouseUnitRepository unitRepository = mock(ProductionOrderWarehouseUnitRepository.class);
    private final ProductionTaskLifecycleService service = new ProductionTaskLifecycleService(
            taskRepository, taskItemRepository, productionOrderRepository, unitRepository);

    private ProductionOrderEntity opv5;
    private ProductionOrderEntity opv6;
    private TaskEntity task;

    @BeforeEach
    void setUp() {
        opv5 = ProductionOrderEntity.builder().id(5L).code("OPV-5").status("IN_PROGRESS").build();
        opv6 = ProductionOrderEntity.builder().id(6L).code("OPV-6").status("IN_PROGRESS").build();
        task = TaskEntity.builder().id(90L).productionOrderId(5L).status("COMPLETED").build();
        when(productionOrderRepository.findById(5L)).thenReturn(Optional.of(opv5));
        when(productionOrderRepository.findById(6L)).thenReturn(Optional.of(opv6));
        when(taskRepository.findByProductionOrderId(5L)).thenReturn(List.of(task));
        when(taskRepository.findByProductionOrderId(6L)).thenReturn(List.of());
        when(taskItemRepository.findBaseLineOrderIdsByTaskId(90L)).thenReturn(List.of(5L, 6L));
        when(taskItemRepository.findBaseLineTaskIdsByProductionOrderId(5L)).thenReturn(List.of(90L));
        when(taskItemRepository.findBaseLineTaskIdsByProductionOrderId(6L)).thenReturn(List.of(90L));
        when(taskRepository.findAllById(List.of(90L))).thenReturn(List.of(task));
    }

    @Test
    @DisplayName("Terminar la consolidada completa todas sus OPV")
    void completaTodasLasOpv() {
        service.syncProductionOrderStatusesOfTask(task);

        assertThat(opv5.getStatus()).isEqualTo("COMPLETED");
        assertThat(opv6.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("La OPV secundaria sigue en proceso mientras la consolidada no termine")
    void secundariaSigueEnProceso() {
        opv6.setStatus("PENDING");
        TaskEntity enCurso = TaskEntity.builder().id(90L).productionOrderId(5L).status("IN_PROGRESS").build();
        when(taskRepository.findAllById(List.of(90L))).thenReturn(List.of(enCurso));

        service.syncProductionOrderStatusFromTasks(6L);

        assertThat(opv6.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("Espera a bodega si cualquiera de sus OPV ya tiene unidades")
    void esperaBodegaPorCualquierOpv() {
        when(unitRepository.countByProductionOrderId(5L)).thenReturn(0L);
        when(unitRepository.countByProductionOrderId(6L)).thenReturn(3L);

        assertThat(service.shouldDeferCompletionToWarehouse(task)).isTrue();
    }
}
