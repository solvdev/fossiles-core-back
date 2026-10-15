package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest.ManualTaskItemRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEventEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskEventRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pausa, tiempo trabajado, atraso por tarea, perdón e historial. */
class TaskWorkflowServiceTest {

    private static final LocalDateTime JUE_16 = LocalDateTime.of(2026, 10, 15, 16, 0);
    private static final LocalDateTime VIE_0900 = LocalDateTime.of(2026, 10, 16, 9, 0);
    private static final LocalDateTime VIE_1030 = LocalDateTime.of(2026, 10, 16, 10, 30);

    private OrganizerTestKit kit;
    private final List<TaskEventEntity> events = new ArrayList<>();
    private final List<TaskItemEntity> taskItems = new ArrayList<>();
    private TaskWorkflowService service;
    private TaskEntity task;
    private long eventSeq;

    @BeforeEach
    void setUp() {
        events.clear();
        taskItems.clear();
        kit = new OrganizerTestKit();
        TaskEventRepository eventRepo = mock(TaskEventRepository.class);
        when(eventRepo.save(any(TaskEventEntity.class))).thenAnswer(inv -> {
            TaskEventEntity e = inv.getArgument(0);
            e.setId(++eventSeq);
            events.add(e);
            return e;
        });
        when(eventRepo.findByTaskIdOrderByCreatedAtAscIdAsc(anyLong())).thenAnswer(inv -> events.stream()
                .filter(e -> e.getTaskId().equals(inv.<Long>getArgument(0)))
                .sorted(Comparator.comparing(TaskEventEntity::getCreatedAt).thenComparing(TaskEventEntity::getId))
                .toList());
        when(kit.taskItemRepository.findByTaskId(anyLong())).thenAnswer(inv -> taskItems.stream()
                .filter(i -> i.getTaskId().equals(inv.<Long>getArgument(0))).toList());
        when(kit.taskRepository.findById(anyLong())).thenAnswer(inv ->
                task != null && task.getId().equals(inv.<Long>getArgument(0)) ? Optional.of(task) : Optional.empty());
        SecurityUtil security = mock(SecurityUtil.class);
        when(security.getCurrentUserId()).thenReturn(7L);

        service = new TaskWorkflowService(kit.taskRepository, kit.taskItemRepository, eventRepo, kit.service,
                new TaskDeskHoursService(kit.taskItemRepository), mock(ProductionTaskLifecycleService.class), security);

        kit.order(1);
        kit.product(10, 1.0);
        kit.item(100, 1, 10, 20);
        // Tarea de 3 h (3 unidades de 1 h), iniciada el jueves a las 16:00.
        task = TaskEntity.builder().id(50L).status("IN_PROGRESS").startedAt(JUE_16)
                .productionOrderId(1L).productionOrderCode("OPK-1").estimatedHours(3.0).quantity(3).build();
        taskItems.add(TaskItemEntity.builder().id(1L).taskId(50L).productionOrderItemId(100L).quantity(3)
                .estimatedHours(3.0).daySaleExtra(false).build());
        service.record(50L, TaskEventEntity.START, null, null, JUE_16);
    }

    private TaskEntity finishAt(LocalDateTime at) {
        service.onStatusChange(task, "IN_PROGRESS", "COMPLETED", null, at);
        return task;
    }

    // ==================== atraso ====================

    @Test
    @DisplayName("Empieza 16:00 y dura 3 h: 1 h hoy y 2 h mañana, termina a las 9:00 y es comisionable")
    void arrastreATiempo() {
        TaskEntity t = finishAt(VIE_0900);

        assertThat(t.getExpectedMinutes()).isEqualTo(180);
        assertThat(t.getDelayed()).isFalse();
        assertThat(t.getCommissionEligible()).isTrue();
        assertThat(service.workedMinutes(t, VIE_0900)).isEqualTo(180);
    }

    @Test
    @DisplayName("Termina a las 10:30: pasa de esperado + 30 min, queda atrasada y sin comisión")
    void arrastreAtrasado() {
        TaskEntity t = finishAt(VIE_1030);

        assertThat(t.getDelayed()).isTrue();
        assertThat(t.getCommissionEligible()).isFalse();
        assertThat(t.getDelayMinutes()).isEqualTo(90);
        assertThat(events).extracting(TaskEventEntity::getEventType)
                .containsExactly("START", "COMPLETE", "DELAY_MARKED");
    }

    @Test
    @DisplayName("La tolerancia son exactamente 30 min: 9:30 pasa, 9:31 no")
    void toleranciaDeTreintaMinutos() {
        assertThat(finishAt(VIE_0900.plusMinutes(30)).getDelayed()).isFalse();

        setUp();
        assertThat(finishAt(VIE_0900.plusMinutes(31)).getDelayed()).isTrue();
    }

    @Test
    @DisplayName("Una tarea 100 % OPL nunca se atrasa")
    void oplNuncaSeAtrasa() {
        task.setProductionOrderCode("OPL-9");
        assertThat(finishAt(VIE_1030).getDelayed()).isFalse();
        assertThat(task.getCommissionEligible()).isTrue();
    }

    @Test
    @DisplayName("El esperado suma todas las líneas, OPL combinadas incluidas")
    void esperadoIncluyeOpl() {
        taskItems.add(TaskItemEntity.builder().id(2L).taskId(50L).productionOrderItemId(200L).quantity(2)
                .estimatedHours(2.0).daySaleExtra(true).build());
        assertThat(service.expectedMinutes(task)).isEqualTo(300);
    }

    // ==================== pausa ====================

    @Test
    @DisplayName("El tiempo en pausa no cuenta")
    void pausaNoCuenta() {
        // 16:00-17:00 (60) | pausa viernes 07:00-08:00 | 08:00-09:00 (60) -> 120 trabajados
        service.record(50L, TaskEventEntity.PAUSE, "Falta material", null, LocalDateTime.of(2026, 10, 16, 7, 0));
        service.record(50L, TaskEventEntity.RESUME, null, null, LocalDateTime.of(2026, 10, 16, 8, 0));

        assertThat(service.workedMinutes(task, VIE_0900)).isEqualTo(120);
    }

    @Test
    @DisplayName("Pausar y reanudar exigen estado, y pausar exige motivo")
    void pausarYReanudar() throws Exception {
        assertThatThrownBy(() -> service.pause(50L, " ")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("motivo");

        TaskEntity paused = service.pause(50L, "Almuerzo largo");
        assertThat(paused.getPausedAt()).isNotNull();
        assertThatThrownBy(() -> service.pause(50L, "otra")).hasMessageContaining("ya está pausada");

        assertThat(service.resume(50L, null).getPausedAt()).isNull();
        assertThatThrownBy(() -> service.resume(50L, null)).hasMessageContaining("no está pausada");
        assertThat(events).extracting(TaskEventEntity::getEventType).containsExactly("START", "PAUSE", "RESUME");
        assertThat(events.get(1).getReason()).isEqualTo("Almuerzo largo");
        assertThat(events.get(1).getCreatedBy()).isEqualTo(7L);

        task.setStatus("PENDING");
        assertThatThrownBy(() -> service.pause(50L, "x")).hasMessageContaining("en proceso");
    }

    @Test
    @DisplayName("Una tarea iniciada antes del historial usa startedAt como primer tramo")
    void tareaAnteriorAlHistorial() {
        events.clear();
        assertThat(service.workedMinutes(task, VIE_0900)).isEqualTo(180);
    }

    @Test
    @DisplayName("Cancelar y volver a PENDING quedan en el historial con su motivo")
    void cancelarQuedaEnElHistorial() {
        service.onStatusChange(task, "IN_PROGRESS", "CANCELLED", "Se canceló el pedido", VIE_0900);

        assertThat(events).extracting(TaskEventEntity::getEventType).containsExactly("START", "CANCEL");
        assertThat(events.get(1).getReason()).isEqualTo("Se canceló el pedido");
    }

    // ==================== perdón ====================

    @Test
    @DisplayName("Perdonar deja la tarea comisionable con motivo, usuario y fecha; una sola vez")
    void perdonarAtraso() throws Exception {
        assertThatThrownBy(() -> service.forgiveDelay(50L, "ok")).hasMessageContaining("no tiene atraso");
        finishAt(VIE_1030);
        assertThatThrownBy(() -> service.forgiveDelay(50L, " ")).hasMessageContaining("motivo");

        TaskEntity t = service.forgiveDelay(50L, "Se cortó la luz");

        assertThat(t.getCommissionEligible()).isTrue();
        assertThat(t.getDelayed()).isTrue();   // el atraso queda en el registro
        assertThat(t.getDelayForgivenBy()).isEqualTo(7L);
        assertThat(t.getDelayForgivenAt()).isNotNull();
        assertThat(t.getDelayForgivenReason()).isEqualTo("Se cortó la luz");
        assertThat(events).extracting(TaskEventEntity::getEventType).endsWith("DELAY_FORGIVEN");
        assertThatThrownBy(() -> service.forgiveDelay(50L, "otra vez")).hasMessageContaining("ya fue perdonado");
    }

    // ==================== agregar productos ====================

    @Test
    @DisplayName("Agregar productos sube cantidad, horas y esperado, y deja evento")
    void agregarProductos() throws Exception {
        TaskEntity t = service.addItems(50L, List.of(
                ManualTaskItemRequest.builder().productionOrderItemId(100L).quantity(1).build()));

        assertThat(t.getQuantity()).isEqualTo(4);
        assertThat(t.getEstimatedHours()).isEqualTo(4.0);
        // El ítem ya estaba en la tarea: se junta la cantidad en la misma línea.
        assertThat(taskItems).hasSize(1);
        assertThat(taskItems.get(0).getQuantity()).isEqualTo(4);
        assertThat(t.getExpectedMinutes()).isEqualTo(240);
        assertThat(events).extracting(TaskEventEntity::getEventType).endsWith("ITEMS_ADDED");
    }

    @Test
    @DisplayName("No se pasa del tope de 5 h base al agregar, ni se agrega a una tarea cerrada")
    void agregarRespetaTope() {
        assertThatThrownBy(() -> service.addItems(50L, List.of(
                ManualTaskItemRequest.builder().productionOrderItemId(100L).quantity(3).build())))
                .hasMessageContaining("máximo");

        task.setStatus("COMPLETED");
        assertThatThrownBy(() -> service.addItems(50L, List.of(
                ManualTaskItemRequest.builder().productionOrderItemId(100L).quantity(1).build())))
                .hasMessageContaining("pendiente o en proceso");
    }
}
