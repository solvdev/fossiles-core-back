package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DeskCountDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionAutoPlanResult;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemMaterialPickRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.DeskSlotFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El autoplan llena solo el día elegido, crea las tareas sin mesa una sola vez, y regenerar
 * no borra tareas que ya tienen avance.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionAutoPlannerDayPlanTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskOrganizerService taskOrganizerService;
    @Mock private LeatherRequirementService leatherRequirementService;
    @Mock private ProductionDeskCountService productionDeskCountService;
    @Mock private SmartMaterialRequestService smartMaterialRequestService;
    @Mock private ProductionPlanningLock productionPlanningLock;
    @Mock private TaskDeskHoursService taskDeskHoursService;
    @Mock private ObjectProvider<ProductionAutoPlannerService> selfProvider;
    @Mock private ProductionTaskLifecycleService productionTaskLifecycleService;
    @Mock private TaskItemMaterialPickRepository taskItemMaterialPickRepository;

    private ProductionAutoPlannerService service;
    private LocalDate day;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() throws Exception {
        service = new ProductionAutoPlannerService(
                productionOrderRepository, productionOrderItemRepository, productRepository, colorRepository,
                taskItemRepository, taskRepository, taskOrganizerService, leatherRequirementService,
                productionDeskCountService, smartMaterialRequestService, productionPlanningLock,
                taskDeskHoursService, selfProvider, productionTaskLifecycleService, taskItemMaterialPickRepository);

        day = DeskSlotFinder.nextWorkday(LocalDate.now().plusDays(30));

        // 2 mesas = 8 h de cupo. Producto de 1 h por unidad, 2 unidades por bloque, 12 unidades:
        // 12 h en grupos de 4 h -> caben 2 grupos, el tercero queda para otro día.
        ProductionOrderEntity po = ProductionOrderEntity.builder().id(5L).code("OP-5").status("PENDING").build();
        ProductEntity product = ProductEntity.builder().id(9L).code("BIL-1").name("Billetera").prdTime(1.0).unitsPerTask(2).build();
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder().id(50L).productionOrderId(5L)
                .productId(9L).quantity(12).build();

        when(productionOrderRepository.findById(5L)).thenReturn(Optional.of(po));
        when(productionOrderItemRepository.findByProductionOrderId(5L)).thenReturn(List.of(item));
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of());
        when(productRepository.findAllById(any())).thenReturn(List.of(product));
        when(productionDeskCountService.getDay(any())).thenReturn(DeskCountDayResponse.builder().numDesks(2).build());
        when(leatherRequirementService.committedFt2ByMaterial()).thenReturn(Map.of());
        when(leatherRequirementService.resolveNeed(any(), any(), anyInt()))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(null, BigDecimal.ZERO));
        when(leatherRequirementService.canCover(any(), anyMap())).thenReturn(true);
        when(taskDeskHoursService.daySaleExtraByTaskId(any())).thenReturn(Map.of());
        when(taskRepository.findByScheduledDate(day)).thenReturn(List.of());
        when(taskOrganizerService.createAutoCentroTask(any()))
                .thenAnswer(inv -> TaskEntity.builder().id(ids.incrementAndGet()).build());
    }

    @Test
    void llenaSoloElDiaElegidoYDejaElRestoEnLaOp() throws Exception {
        ProductionAutoPlanResult result = service.planOrder(5L, day);

        ArgumentCaptor<CreateManualTaskRequest> created = ArgumentCaptor.forClass(CreateManualTaskRequest.class);
        verify(taskOrganizerService, times(2)).createAutoCentroTask(created.capture());
        assertThat(created.getAllValues()).allSatisfy(req -> {
            assertThat(req.getDesk()).as("la mesa la pone el troquelado").isNull();
            assertThat(req.getScheduledDate()).isEqualTo(day);
            assertThat(req.getItems()).singleElement().satisfies(l -> assertThat(l.getQuantity()).isEqualTo(4));
        });
        assertThat(result.getCentroTasksCreated()).isEqualTo(2);
        assertThat(result.getDeferredNoCapacity()).singleElement()
                .satisfies(line -> assertThat(line.getRemainingQuantity()).isEqualTo(4));
    }

    @Test
    void cuentaLasTareasSinMesaDelDiaComoCarga() throws Exception {
        TaskEntity a = TaskEntity.builder().id(1L).status("PENDING").scheduledDate(day).build();
        TaskEntity b = TaskEntity.builder().id(2L).status("PENDING").scheduledDate(day).build();
        when(taskRepository.findByScheduledDate(day)).thenReturn(List.of(a, b));
        when(taskDeskHoursService.baseHours(any(TaskEntity.class), anyMap())).thenReturn(4.0);

        ProductionAutoPlanResult result = service.planOrder(5L, day);

        verify(taskOrganizerService, never()).createAutoCentroTask(any());
        assertThat(result.getDeferredNoCapacity()).singleElement()
                .satisfies(line -> assertThat(line.getRemainingQuantity()).isEqualTo(12));
    }

    @Test
    void regenerarConservaLasTareasConAvance() throws Exception {
        TaskEntity cortada = TaskEntity.builder().id(1L).status("PENDING").observations("Auto-plan")
                .dieCutReady(true).build();
        TaskEntity intacta = TaskEntity.builder().id(2L).status("PENDING").observations("Auto-plan").build();
        TaskEntity cinchoIntacta = TaskEntity.builder().id(3L).status("PENDING").observations("Auto-plan cinchos")
                .dieCutReady(true).leatherDelivered(true).build();
        when(taskRepository.findByStatus("PENDING")).thenReturn(List.of(cortada, intacta, cinchoIntacta));
        when(taskItemRepository.findByTaskIdIn(any())).thenReturn(List.of());
        when(productionOrderRepository.findActiveOrders()).thenReturn(List.of());

        ProductionAutoPlanResult result = service.regenerate(null, day);

        verify(taskRepository, never()).deleteById(1L);
        verify(taskRepository).deleteById(2L);
        verify(taskRepository).deleteById(3L);
        verify(taskRepository, times(2)).deleteById(anyLong());
        assertThat(result.getClearedAutoPlanTasks()).isEqualTo(2);
        assertThat(result.getKeptWithProgress()).isEqualTo(1);
    }
}
