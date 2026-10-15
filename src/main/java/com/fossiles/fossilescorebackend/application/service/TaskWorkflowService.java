package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEventEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskEventRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionShift;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ciclo de vida de la tarea en mesa: historial, pausa, atraso y comisión.
 *
 * <p><b>Atraso por tarea</b> (no por producto: no hay forma de saber cuánto tardó cada uno).
 * Tiempo esperado = suma de las horas de todas las líneas de la tarea, OPL incluidas. Tiempo
 * trabajado = minutos de jornada de los tramos iniciar→pausar, sin noches, almuerzo ni pausas.
 * Atrasada si trabajado &gt; esperado + {@value #DELAY_TOLERANCE_MINUTES} min; entonces la tarea
 * entera deja de ser comisionable. El atraso se fija al completar y no se revierte: solo se
 * perdona, con motivo, usuario y fecha. Las tareas de OPL nunca se atrasan.
 *
 * <p>Todo queda en {@code task_event}, que solo se agrega.
 */
@Service
@RequiredArgsConstructor
public class TaskWorkflowService {

    /** Tolerancia fija: no es configurable a propósito. */
    public static final int DELAY_TOLERANCE_MINUTES = 30;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskEventRepository taskEventRepository;
    private final TaskOrganizerService taskOrganizerService;
    private final TaskDeskHoursService taskDeskHoursService;
    private final ProductionTaskLifecycleService productionTaskLifecycleService;
    private final SecurityUtil securityUtil;

    // ==================== historial ====================

    public TaskEventEntity record(Long taskId, String type, String reason, String detail, LocalDateTime at) {
        return taskEventRepository.save(TaskEventEntity.builder()
                .taskId(taskId).eventType(type).reason(blankToNull(reason)).detail(detail)
                .createdBy(securityUtil.getCurrentUserId())
                .createdAt(at != null ? at : GuatemalaDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<TaskEventEntity> history(Long taskId) throws ResourceNotFoundException {
        taskRepository.findById(taskId).orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        return taskEventRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId);
    }

    // ==================== cambios de estado (los dispara PUT /status) ====================

    /**
     * Registra el cambio de estado y, al terminar el trabajo, mide el atraso.
     *
     * @return minutos trabajados al terminar (para {@code actualDurationMinutes}), o
     *         {@code null} si el cambio no es una terminación
     */
    public Integer onStatusChange(TaskEntity task, String from, String to, String reason, LocalDateTime now) {
        task.setPausedAt(null);
        switch (to) {
            case "IN_PROGRESS" -> {
                if ("PENDING".equals(from)) {
                    record(task.getId(), TaskEventEntity.START, reason, null, now);
                }
            }
            case "PENDING" -> {
                if (!"PENDING".equals(from)) {
                    record(task.getId(), TaskEventEntity.RESET, reason, "Vuelve a PENDING desde " + from, now);
                }
            }
            case "CANCELLED" -> record(task.getId(), TaskEventEntity.CANCEL, reason,
                    "Estado anterior: " + from, now);
            case "COMPLETED", ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE -> {
                boolean awaiting = !"COMPLETED".equals(to);
                record(task.getId(), awaiting ? TaskEventEntity.AWAITING_WAREHOUSE : TaskEventEntity.COMPLETE,
                        reason, null, now);
                return evaluateDelay(task, now);
            }
            default -> { }
        }
        return null;
    }

    // ==================== pausa ====================

    @Transactional(rollbackFor = Exception.class)
    public TaskEntity pause(Long taskId, String reason) throws ResourceNotFoundException, BusinessException {
        TaskEntity task = load(taskId);
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new BusinessException("Solo se puede pausar una tarea en proceso.");
        }
        if (task.getPausedAt() != null) {
            throw new BusinessException("La tarea ya está pausada.");
        }
        requireReason(reason, "Indique el motivo de la pausa.");
        LocalDateTime now = GuatemalaDateTime.now();
        task.setPausedAt(now);
        record(taskId, TaskEventEntity.PAUSE, reason, null, now);
        return taskRepository.save(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskEntity resume(Long taskId, String reason) throws ResourceNotFoundException, BusinessException {
        TaskEntity task = load(taskId);
        if (!"IN_PROGRESS".equals(task.getStatus()) || task.getPausedAt() == null) {
            throw new BusinessException("La tarea no está pausada.");
        }
        LocalDateTime now = GuatemalaDateTime.now();
        task.setPausedAt(null);
        record(taskId, TaskEventEntity.RESUME, reason, null, now);
        return taskRepository.save(task);
    }

    // ==================== atraso ====================

    /**
     * Minutos de jornada realmente trabajados: suma de los tramos que abren START/RESUME y
     * cierran PAUSE/COMPLETE/CANCEL/RESET (o {@code end} si sigue corriendo). Una tarea
     * iniciada antes de existir el historial usa {@code startedAt} como primer tramo.
     */
    public int workedMinutes(TaskEntity task, LocalDateTime end) {
        List<TaskEventEntity> events = task.getId() == null ? List.of()
                : taskEventRepository.findByTaskIdOrderByCreatedAtAscIdAsc(task.getId());
        boolean hasStart = events.stream().anyMatch(e -> TaskEventEntity.START.equals(e.getEventType()));
        LocalDateTime open = !hasStart ? task.getStartedAt() : null;
        long total = 0;
        for (TaskEventEntity e : events) {
            switch (e.getEventType()) {
                case TaskEventEntity.START, TaskEventEntity.RESUME -> {
                    if (open == null) {
                        open = e.getCreatedAt();
                    }
                }
                case TaskEventEntity.PAUSE, TaskEventEntity.COMPLETE, TaskEventEntity.AWAITING_WAREHOUSE,
                     TaskEventEntity.CANCEL -> {
                    if (open != null) {
                        total += ProductionShift.workingMinutesBetween(open, e.getCreatedAt());
                        open = null;
                    }
                }
                case TaskEventEntity.RESET -> {
                    total = 0;
                    open = null;
                }
                default -> { }
            }
        }
        if (open != null && task.getPausedAt() == null) {
            total += ProductionShift.workingMinutesBetween(open, end);
        }
        return (int) Math.max(0, total);
    }

    /** Suma de las horas de todas las líneas (OPL incluidas), en minutos. */
    public int expectedMinutes(TaskEntity task) {
        List<TaskItemEntity> items = task.getId() == null ? List.of() : taskItemRepository.findByTaskId(task.getId());
        double hours = items.isEmpty()
                ? (task.getEstimatedHours() == null ? 0.0 : task.getEstimatedHours())
                : items.stream().mapToDouble(i -> i.getEstimatedHours() == null ? 0.0 : i.getEstimatedHours()).sum();
        return (int) Math.round(hours * 60.0);
    }

    /** Fija esperado, trabajado y atraso al terminar el trabajo. Devuelve los minutos trabajados. */
    public int evaluateDelay(TaskEntity task, LocalDateTime finishedAt) {
        int worked = workedMinutes(task, finishedAt);
        int expected = expectedMinutes(task);
        task.setExpectedMinutes(expected);
        boolean onlineSale = ProductionPlanningConstants.isOnlineSaleOrder(null, task.getProductionOrderCode());
        int over = Math.max(0, worked - expected);
        task.setDelayMinutes(over);
        boolean delayed = !onlineSale && worked > expected + DELAY_TOLERANCE_MINUTES;
        task.setDelayed(delayed);
        task.setCommissionEligible(!delayed);
        if (delayed) {
            record(task.getId(), TaskEventEntity.DELAY_MARKED, null, String.format(Locale.ROOT,
                    "Trabajado %d min, esperado %d min (+%d de tolerancia): atraso de %d min. "
                            + "La tarea ya no es comisionable.",
                    worked, expected, DELAY_TOLERANCE_MINUTES, over), finishedAt);
        }
        return worked;
    }

    /** El jefe perdona el atraso: la tarea vuelve a ser comisionable y queda quién, cuándo y por qué. */
    @Transactional(rollbackFor = Exception.class)
    public TaskEntity forgiveDelay(Long taskId, String reason) throws ResourceNotFoundException, BusinessException {
        TaskEntity task = load(taskId);
        if (!Boolean.TRUE.equals(task.getDelayed())) {
            throw new BusinessException("La tarea no tiene atraso que perdonar.");
        }
        if (task.getDelayForgivenAt() != null) {
            throw new BusinessException("El atraso de esta tarea ya fue perdonado.");
        }
        requireReason(reason, "Indique el motivo para perdonar el atraso.");
        LocalDateTime now = GuatemalaDateTime.now();
        task.setDelayForgivenBy(securityUtil.getCurrentUserId());
        task.setDelayForgivenAt(now);
        task.setDelayForgivenReason(reason.trim());
        task.setCommissionEligible(true);
        record(taskId, TaskEventEntity.DELAY_FORGIVEN, reason, null, now);
        return taskRepository.save(task);
    }

    // ==================== agregar productos a una tarea ====================

    /**
     * Agrega productos a una tarea abierta (típico: la que se arrastra al día siguiente). Sube el
     * tiempo esperado de la tarea; no hereda ni borra atraso.
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskEntity addItems(Long taskId, List<CreateManualTaskRequest.ManualTaskItemRequest> lines)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity task = load(taskId);
        if (!"PENDING".equals(task.getStatus()) && !"IN_PROGRESS".equals(task.getStatus())) {
            throw new BusinessException("Solo se agregan productos a una tarea pendiente o en proceso.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("Indique al menos un producto.");
        }
        CreateManualTaskRequest request = CreateManualTaskRequest.builder()
                .productionOrderId(task.getProductionOrderId()).items(lines).build();
        TaskOrganizerService.TaskDraft draft = taskOrganizerService.prepareDraft(
                request, TaskOrganizerService.CreateMode.MANUAL, true, Map.of());
        double cap = ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP;
        double newBase = taskDeskHoursService.baseHours(task) + draft.baseHours();
        if (newBase > cap + 1e-9) {
            throw new BusinessException(String.format(Locale.ROOT,
                    "Con lo que se agrega la tarea llega a %.2f h base y el máximo es %.1f h.", newBase, cap));
        }
        taskOrganizerService.appendDraftToTask(task, draft);
        task.setExpectedMinutes(expectedMinutes(task));
        StringBuilder detail = new StringBuilder();
        for (TaskItemEntity item : draft.items()) {
            detail.append(item.getProductionOrderItemId()).append(" x").append(item.getQuantity()).append("; ");
        }
        record(taskId, TaskEventEntity.ITEMS_ADDED, null, detail.toString().trim(), GuatemalaDateTime.now());
        TaskEntity saved = taskRepository.save(task);
        productionTaskLifecycleService.syncProductionOrderStatusFromTasks(saved.getProductionOrderId());
        return saved;
    }

    // ---------------------------------------------------------------- helpers

    private TaskEntity load(Long taskId) throws ResourceNotFoundException {
        return taskRepository.findById(taskId).orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
    }

    private static void requireReason(String reason, String message) throws BusinessException {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(message);
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
