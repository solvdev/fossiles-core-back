package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.MaterialsTaskViewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaskResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaskTicketResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.MaterialConsumptionService;
import com.fossiles.fossilescorebackend.application.service.ProductionTaskGenerationService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final double MAX_HOURS_PER_DESK_PER_DAY = 4.0;
    private static final double DEFAULT_PRD_TIME_PER_UNIT = 0.1; // hours per unit if not configured
    private static final int MAX_DESKS = 12;
    private static final List<String> DESKS_COUNT_CONFIG_KEYS = List.of(
            "MANUFACTURING_NUMBER_OF_TABLES",
            "PRODUCTION_TABLES_COUNT"
    );
    private static final ZoneId GUATEMALA_ZONE = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter HOUR_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ColorRepository colorRepository;
    private final BomRepository bomRepository;
    private final BomItemRepository bomItemRepository;
    private final MaterialRepository materialRepository;
    private final LeatherMovementRepository leatherMovementRepository;
    private final MaterialConsumptionService materialConsumptionService;
    private final ProductionTaskGenerationService productionTaskGenerationService;

    // ==================== CRUD ====================

    @GetMapping
    public ResponseEntity<List<TaskResponse>> getAll() {
        List<TaskResponse> tasks = taskRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/{id}/ticket")
    public ResponseEntity<TaskTicketResponse> getTicket(@PathVariable Long id) throws ResourceNotFoundException {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        return ResponseEntity.ok(buildTicket(task));
    }

    @GetMapping("/production-order/{productionOrderId}/tickets")
    public ResponseEntity<List<TaskTicketResponse>> getTicketsByProductionOrder(@PathVariable Long productionOrderId) {
        List<TaskTicketResponse> tickets = findTasksLinkedToProductionOrder(productionOrderId).stream()
                .map(this::buildTicket)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tickets);
    }

    @GetMapping("/production-order/{productionOrderId}")
    public ResponseEntity<List<TaskResponse>> getByProductionOrder(@PathVariable Long productionOrderId) {
        List<TaskResponse> tasks = findTasksLinkedToProductionOrder(productionOrderId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/desk/{desk}")
    public ResponseEntity<List<TaskResponse>> getByDesk(@PathVariable Integer desk) {
        List<TaskResponse> tasks = taskRepository.findByDesk(desk).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<List<TaskResponse>> getByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<TaskResponse> tasks = taskRepository.findByScheduledDate(date).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/queue")
    public ResponseEntity<List<TaskResponse>> getQueue() {
        List<TaskResponse> tasks = taskRepository.findPendingAndInProgressOrdered().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/schedule-dates")
    public ResponseEntity<List<LocalDate>> getScheduleDates() {
        return ResponseEntity.ok(taskRepository.findDistinctScheduledDates());
    }

    @GetMapping("/desks-count")
    public ResponseEntity<Map<String, Object>> getDesksCount() {
        DesksCountResolution resolution = resolveNumDesks();
        return ResponseEntity.ok(Map.of(
                "count", resolution.count(),
                "resolvedKey", resolution.resolvedKey(),
                "isDefault", resolution.isDefault()));
    }

    @PostMapping("/optimize-pending")
    @Transactional
    public ResponseEntity<Map<String, Object>> optimizePendingTasks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean dryRun) {

        List<TaskEntity> candidates = taskRepository.findPendingAndInProgressOrdered().stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> t.getScheduledDate() != null && t.getDesk() != null)
                .filter(t -> date == null || date.equals(t.getScheduledDate()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "mergedTasks", 0,
                    "dryRun", dryRun,
                    "message", "No hay tareas pendientes programadas para optimizar."));
        }

        Map<Long, TaskEntity> taskById = candidates.stream().collect(Collectors.toMap(TaskEntity::getId, t -> t));
        List<Long> taskIds = candidates.stream().map(TaskEntity::getId).toList();
        List<TaskItemEntity> allTaskItems = taskItemRepository.findByTaskIdIn(taskIds);
        Map<Long, List<TaskItemEntity>> itemsByTask = allTaskItems.stream()
                .collect(Collectors.groupingBy(TaskItemEntity::getTaskId));

        Map<DeskDateKey, List<TaskEntity>> byDeskDate = candidates.stream()
                .collect(Collectors.groupingBy(t -> new DeskDateKey(t.getScheduledDate(), t.getDesk())));

        int mergedTasks = 0;
        int checkedGroups = 0;

        for (Map.Entry<DeskDateKey, List<TaskEntity>> entry : byDeskDate.entrySet()) {
            List<TaskEntity> group = new ArrayList<>(entry.getValue());
            if (group.size() < 2) continue;
            checkedGroups++;

            group.sort(Comparator
                    .comparing(TaskEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(t -> t.getPriority(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TaskEntity::getId));

            Set<Long> removed = new HashSet<>();
            for (int i = 0; i < group.size(); i++) {
                TaskEntity base = group.get(i);
                if (removed.contains(base.getId())) continue;

                double baseHours = base.getEstimatedHours() != null ? base.getEstimatedHours() : 0.0;
                boolean baseTouched = false;

                for (int j = i + 1; j < group.size(); j++) {
                    TaskEntity donor = group.get(j);
                    if (removed.contains(donor.getId())) continue;
                    if (!canMergeTasks(base, donor)) continue;

                    double donorHours = donor.getEstimatedHours() != null ? donor.getEstimatedHours() : 0.0;
                    if (baseHours + donorHours > MAX_HOURS_PER_DESK_PER_DAY + 1e-9) continue;

                    mergedTasks++;
                    baseHours += donorHours;
                    baseTouched = true;

                    if (!dryRun) {
                        List<TaskItemEntity> donorItems = new ArrayList<>(itemsByTask.getOrDefault(donor.getId(), List.of()));
                        donorItems.forEach(item -> item.setTaskId(base.getId()));
                        if (!donorItems.isEmpty()) {
                            taskItemRepository.saveAll(donorItems);
                        }

                        itemsByTask.computeIfAbsent(base.getId(), k -> new ArrayList<>()).addAll(donorItems);
                        itemsByTask.remove(donor.getId());

                        base.setEstimatedHours(roundHours(baseHours));
                        base.setQuantity((base.getQuantity() != null ? base.getQuantity() : 0)
                                + (donor.getQuantity() != null ? donor.getQuantity() : 0));
                        if (base.getPriority() == null || (donor.getPriority() != null && donor.getPriority() < base.getPriority())) {
                            base.setPriority(donor.getPriority());
                        }
                        base.setDeliveryDate(minDate(base.getDeliveryDate(), donor.getDeliveryDate()));
                        taskById.remove(donor.getId());
                        taskRepository.deleteById(donor.getId());
                    }

                    removed.add(donor.getId());
                }

                if (baseTouched && !dryRun) {
                    taskRepository.save(base);
                }
            }
        }

        String msg = dryRun
                ? "Simulación completada: se podrían fusionar " + mergedTasks + " tarea(s)."
                : "Optimización aplicada: se fusionaron " + mergedTasks + " tarea(s).";

        return ResponseEntity.ok(Map.of(
                "mergedTasks", mergedTasks,
                "groupsChecked", checkedGroups,
                "dryRun", dryRun,
                "message", msg
        ));
    }

    @PostMapping("/rebalance-day")
    @Transactional
    public ResponseEntity<Map<String, Object>> rebalanceDayTasks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer desksCount) {

        int maxConfiguredDesks = getNumDesks();
        int activeDesks = desksCount == null ? maxConfiguredDesks : Math.max(1, Math.min(desksCount, maxConfiguredDesks));

        List<TaskEntity> candidates = taskRepository.findByScheduledDate(date).stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .filter(t -> !"COMPLETED".equals(t.getStatus()))
                .filter(t -> "PENDING".equals(t.getStatus()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "activeDesks", activeDesks,
                    "updatedTasks", 0,
                    "message", "No hay tareas pendientes para redistribuir en la fecha indicada."));
        }

        Comparator<TaskEntity> byPriorityThenWorkload = Comparator
                .comparing((TaskEntity t) -> -getTaskBaseHours(t))
                .thenComparing(TaskEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getId);

        List<TaskEntity> sorted = candidates.stream()
                .sorted(byPriorityThenWorkload)
                .collect(Collectors.toList());

        Map<Integer, Double> deskLoads = new HashMap<>();
        for (int desk = 1; desk <= activeDesks; desk++) {
            deskLoads.put(desk, 0.0);
        }

        int updated = 0;
        for (TaskEntity task : sorted) {
            int targetDesk = findLeastLoadedDesk(deskLoads);
            double taskHours = getTaskBaseHours(task);
            deskLoads.merge(targetDesk, taskHours, Double::sum);

            if (!Objects.equals(task.getDesk(), targetDesk)) {
                task.setDesk(targetDesk);
                taskRepository.save(task);
                updated++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "date", date,
                "activeDesks", activeDesks,
                "updatedTasks", updated,
                "totalTasks", sorted.size(),
                "message", "Redistribucion completada: " + sorted.size() + " tareas repartidas en " + activeDesks + " mesa(s)."));
    }

    @PostMapping("/plan-window")
    @Transactional
    public ResponseEntity<Map<String, Object>> planWindowTasks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) Integer desksCount,
            @RequestParam(required = false) Integer horizonDays,
            @RequestParam(required = false) Long productionOrderId) {

        int maxConfiguredDesks = getNumDesks();
        int activeDesks = desksCount == null ? maxConfiguredDesks : Math.max(1, Math.min(desksCount, maxConfiguredDesks));
        int days = horizonDays == null ? 5 : Math.max(1, horizonDays);

        LocalDate selectionEndDate = startDate.plusDays(days - 1);

        List<TaskEntity> pool = taskRepository.findPendingAndInProgressOrdered();

        List<TaskEntity> candidates = pool.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> productionOrderId == null || Objects.equals(t.getProductionOrderId(), productionOrderId))
                .filter(t -> {
                    LocalDate scheduledDate = t.getScheduledDate();
                    if (scheduledDate == null) return true;
                    return !scheduledDate.isBefore(startDate) && !scheduledDate.isAfter(selectionEndDate);
                })
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "startDate", startDate,
                    "activeDesks", activeDesks,
                    "horizonDays", days,
                    "selectedTasks", 0,
                    "updatedTasks", 0,
                    "message", "No hay tareas PENDIENTES para planificar en la ventana indicada."));
        }

        Comparator<TaskEntity> byPriorityThenWorkload = Comparator
                .comparing((TaskEntity t) -> -getTaskBaseHours(t))
                .thenComparing(TaskEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getId);

        List<TaskEntity> sorted = candidates.stream()
                .sorted(byPriorityThenWorkload)
                .collect(Collectors.toList());

        Map<LocalDate, Map<Integer, Double>> deskLoadsByDate = new HashMap<>();

        int updated = 0;
        LocalDate maxAssignedDate = startDate;

        for (TaskEntity task : sorted) {
            double taskHours = getTaskBaseHours(task);

            LocalDate targetDate = startDate;
            while (true) {
                Map<Integer, Double> loads = deskLoadsByDate.computeIfAbsent(targetDate, d -> {
                    Map<Integer, Double> m = new HashMap<>();
                    for (int desk = 1; desk <= activeDesks; desk++) m.put(desk, 0.0);
                    return m;
                });

                int targetDesk = findLeastLoadedDesk(loads);
                double currentLoad = loads.getOrDefault(targetDesk, 0.0);

                boolean alwaysFits = taskHours <= MAX_HOURS_PER_DESK_PER_DAY + 1e-9;
                if (!alwaysFits) {
                    // Caso raro: si una sola tarea supera la capacidad diaria, se asigna igual para evitar loops infinitos.
                    loads.put(targetDesk, currentLoad + taskHours);
                    maxAssignedDate = maxAssignedDate.isBefore(targetDate) ? targetDate : maxAssignedDate;

                    if (!Objects.equals(task.getDesk(), targetDesk) || !Objects.equals(task.getScheduledDate(), targetDate)) {
                        task.setDesk(targetDesk);
                        task.setScheduledDate(targetDate);
                        taskRepository.save(task);
                        updated++;
                    }
                    break;
                }

                if (currentLoad + taskHours <= MAX_HOURS_PER_DESK_PER_DAY + 1e-9) {
                    loads.put(targetDesk, currentLoad + taskHours);
                    maxAssignedDate = maxAssignedDate.isBefore(targetDate) ? targetDate : maxAssignedDate;

                    if (!Objects.equals(task.getDesk(), targetDesk) || !Objects.equals(task.getScheduledDate(), targetDate)) {
                        task.setDesk(targetDesk);
                        task.setScheduledDate(targetDate);
                        taskRepository.save(task);
                        updated++;
                    }
                    break;
                }

                // Si el menos cargado no tiene espacio, empuja a la siguiente fecha.
                targetDate = targetDate.plusDays(1);
            }
        }

        return ResponseEntity.ok(Map.of(
                "startDate", startDate,
                "activeDesks", activeDesks,
                "horizonDays", days,
                "selectedTasks", sorted.size(),
                "updatedTasks", updated,
                "maxAssignedDate", maxAssignedDate,
                "message", "Planificación multi-día completada: " + sorted.size()
                        + " tareas planificadas a partir de " + startDate
                        + " (ventana inicial " + days + " día(s))."));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<TaskResponse> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        String newStatus = body.get("status");
        if (newStatus == null || !isValidStatus(newStatus)) {
            throw new BusinessException("Invalid status. Must be: PENDING, IN_PROGRESS, COMPLETED, CANCELLED");
        }

        if ("IN_PROGRESS".equals(newStatus) && !canMoveToInProgress(entity)) {
            throw new BusinessException("No se puede iniciar la tarea. Debe tener cuero entregado, troquelado, mesa asignada y materiales entregados (si aplica).");
        }
        if (!canTransitionStatus(entity.getStatus(), newStatus)) {
            throw new BusinessException("Transición de estado no permitida: " + entity.getStatus() + " -> " + newStatus);
        }
        if ("COMPLETED".equals(newStatus) && entity.getStartedAt() == null) {
            throw new BusinessException("No se puede completar una tarea que no ha sido iniciada.");
        }

        if ("IN_PROGRESS".equals(newStatus)) {
            splitBlockedItemsIntoPendingTask(entity);
        }

        entity.setStatus(newStatus);

        // Time tracking
        if ("IN_PROGRESS".equals(newStatus) && entity.getStartedAt() == null) {
            LocalDateTime gtNow = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDateTime();
            entity.setStartedAt(gtNow);
            entity.setStartTime(gtNow.toLocalTime().format(HOUR_MINUTE_FORMATTER));
        }
        if ("COMPLETED".equals(newStatus) && entity.getCompletedAt() == null) {
            LocalDateTime gtNow = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDateTime();
            entity.setCompletedAt(gtNow);
            // Calculate duration
            if (entity.getStartedAt() != null) {
                long minutes = java.time.Duration.between(entity.getStartedAt(), entity.getCompletedAt()).toMinutes();
                entity.setActualDurationMinutes((int) minutes);
            }
        }
        if ("PENDING".equals(newStatus)) {
            entity.setStartedAt(null);
            entity.setCompletedAt(null);
            entity.setActualDurationMinutes(null);
            entity.setStartTime(null);
        }

        TaskEntity updated = taskRepository.save(entity);
        syncProductionOrderStatusFromTasks(updated.getProductionOrderId());
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/waste")
    public ResponseEntity<TaskResponse> updateWaste(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        if (body.containsKey("wasteQuantity")) {
            entity.setWasteQuantity(Integer.valueOf(body.get("wasteQuantity").toString()));
        }
        if (body.containsKey("wasteNotes")) {
            entity.setWasteNotes(body.get("wasteNotes") != null ? body.get("wasteNotes").toString() : null);
        }

        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/leather-delivery")
    public ResponseEntity<TaskResponse> setLeatherDelivery(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        boolean delivered = body.get("delivered") != null && Boolean.parseBoolean(body.get("delivered").toString());
        if (!delivered && (Boolean.TRUE.equals(entity.getDieCutReady())
                || Boolean.TRUE.equals(entity.getMaterialsDelivered())
                || "IN_PROGRESS".equals(entity.getStatus())
                || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar cuero entregado porque la tarea ya avanzó a una fase posterior.");
        }

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(entity.getId());
        if (!taskItems.isEmpty()) {
            for (TaskItemEntity item : taskItems) {
                item.setLeatherDelivered(delivered);
                item.setLeatherDeliveredAt(delivered ? LocalDateTime.now() : null);
                taskItemRepository.save(item);
            }
            entity.setLeatherDelivered(areTaskItemsLeatherDelivered(entity));
            entity.setLeatherDeliveredAt(Boolean.TRUE.equals(entity.getLeatherDelivered()) ? LocalDateTime.now() : null);
        } else {
            entity.setLeatherDelivered(delivered);
            entity.setLeatherDeliveredAt(delivered ? LocalDateTime.now() : null);
        }
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/leather-delivery/item/{taskItemId}")
    public ResponseEntity<TaskResponse> setTaskItemLeatherDelivery(
            @PathVariable Long id,
            @PathVariable Long taskItemId,
            @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        TaskItemEntity item = taskItemRepository.findById(taskItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", taskItemId));

        if (!Objects.equals(item.getTaskId(), entity.getId())) {
            throw new BusinessException("El item no pertenece a la tarea indicada.");
        }

        boolean delivered = body.get("delivered") != null && Boolean.parseBoolean(body.get("delivered").toString());
        if (!delivered && (Boolean.TRUE.equals(entity.getDieCutReady())
                || Boolean.TRUE.equals(entity.getMaterialsDelivered())
                || "IN_PROGRESS".equals(entity.getStatus())
                || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar cuero entregado porque la tarea ya avanzó a una fase posterior.");
        }

        item.setLeatherDelivered(delivered);
        item.setLeatherDeliveredAt(delivered ? LocalDateTime.now() : null);
        taskItemRepository.save(item);

        entity.setLeatherDelivered(areTaskItemsLeatherDelivered(entity));
        entity.setLeatherDeliveredAt(Boolean.TRUE.equals(entity.getLeatherDelivered()) ? LocalDateTime.now() : null);
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/materials-delivery")
    public ResponseEntity<TaskResponse> setMaterialsDelivery(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        boolean delivered = body.get("delivered") != null && Boolean.parseBoolean(body.get("delivered").toString());
        if (delivered && taskRequiresMaterials(entity) && !canDeliverMaterials(entity)) {
            throw new BusinessException("No se puede entregar materiales aún. Requiere cuero entregado, troquelado y entrada a mesa.");
        }
        if (!delivered && ("IN_PROGRESS".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar materiales entregados cuando la tarea ya está en proceso o completada.");
        }

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(entity.getId());
        if (!taskItems.isEmpty()) {
            if (delivered) {
                ProductionOrderEntity order = entity.getProductionOrderId() != null
                        ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                        : null;
                boolean alreadyConsumedAtOrderLevel = order != null && Boolean.TRUE.equals(order.getMaterialsConsumed());

                for (TaskItemEntity item : taskItems) {
                    if (!isTaskItemRequiresMaterials(item)) {
                        if (!Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                            item.setMaterialsDelivered(true);
                            item.setMaterialsDeliveredAt(LocalDateTime.now());
                            taskItemRepository.save(item);
                        }
                        continue;
                    }
                    if (!Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                        if (!alreadyConsumedAtOrderLevel) {
                            materialConsumptionService.consumeMaterialsForTaskItem(entity.getId(), item.getId());
                        }
                        item.setMaterialsDelivered(true);
                        item.setMaterialsDeliveredAt(LocalDateTime.now());
                        taskItemRepository.save(item);
                    }
                }
            } else {
                for (TaskItemEntity item : taskItems) {
                    if (isTaskItemRequiresMaterials(item)) {
                        item.setMaterialsDelivered(false);
                        item.setMaterialsDeliveredAt(null);
                        taskItemRepository.save(item);
                    }
                }
            }
            entity.setMaterialsDelivered(areRequiredTaskItemsDelivered(entity));
            entity.setMaterialsDeliveredAt(Boolean.TRUE.equals(entity.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        } else {
            if (delivered && !Boolean.TRUE.equals(entity.getMaterialsDelivered())) {
                ProductionOrderEntity order = entity.getProductionOrderId() != null
                        ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                        : null;
                boolean alreadyConsumedAtOrderLevel = order != null && Boolean.TRUE.equals(order.getMaterialsConsumed());
                if (!alreadyConsumedAtOrderLevel) {
                    materialConsumptionService.consumeMaterialsForTask(entity.getId());
                }
            }
            entity.setMaterialsDelivered(delivered);
            entity.setMaterialsDeliveredAt(delivered ? LocalDateTime.now() : null);
        }
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/materials-delivery/item/{taskItemId}")
    public ResponseEntity<TaskResponse> setTaskItemMaterialsDelivery(
            @PathVariable Long id,
            @PathVariable Long taskItemId,
            @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        TaskItemEntity item = taskItemRepository.findById(taskItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", taskItemId));

        if (!Objects.equals(item.getTaskId(), entity.getId())) {
            throw new BusinessException("El item no pertenece a la tarea indicada.");
        }

        boolean delivered = body.get("delivered") != null && Boolean.parseBoolean(body.get("delivered").toString());
        if (!isTaskItemRequiresMaterials(item)) {
            item.setMaterialsDelivered(true);
            item.setMaterialsDeliveredAt(item.getMaterialsDeliveredAt() != null ? item.getMaterialsDeliveredAt() : LocalDateTime.now());
        } else if (delivered) {
            if (!canDeliverMaterialsPreconditions(entity)) {
                throw new BusinessException("No se puede entregar materiales aún. Requiere cuero entregado, troquelado y entrada a mesa.");
            }
            if (!Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                ProductionOrderEntity order = entity.getProductionOrderId() != null
                        ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                        : null;
                boolean alreadyConsumedAtOrderLevel = order != null && Boolean.TRUE.equals(order.getMaterialsConsumed());
                if (!alreadyConsumedAtOrderLevel) {
                    materialConsumptionService.consumeMaterialsForTaskItem(entity.getId(), item.getId());
                }
            }
            item.setMaterialsDelivered(true);
            item.setMaterialsDeliveredAt(LocalDateTime.now());
        } else {
            if ("IN_PROGRESS".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus())) {
                throw new BusinessException("No se puede desmarcar materiales cuando la tarea ya está en proceso o completada.");
            }
            item.setMaterialsDelivered(false);
            item.setMaterialsDeliveredAt(null);
        }

        taskItemRepository.save(item);
        entity.setMaterialsDelivered(areRequiredTaskItemsDelivered(entity));
        entity.setMaterialsDeliveredAt(Boolean.TRUE.equals(entity.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/schedule")
    public ResponseEntity<TaskResponse> scheduleTask(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        if (body.containsKey("scheduledDate")) {
            String dateStr = (String) body.get("scheduledDate");
            entity.setScheduledDate(dateStr != null && !dateStr.isEmpty() ? LocalDate.parse(dateStr) : null);
        }
        if (body.containsKey("desk")) {
            Object deskVal = body.get("desk");
            if (deskVal != null && !Boolean.TRUE.equals(entity.getDieCutReady())) {
                throw new BusinessException("No se puede asignar mesa sin troquelado.");
            }
            entity.setDesk(deskVal != null ? Integer.parseInt(deskVal.toString()) : null);
        }
        if (body.containsKey("deliveryDate")) {
            String dateStr = (String) body.get("deliveryDate");
            entity.setDeliveryDate(dateStr != null && !dateStr.isEmpty() ? LocalDate.parse(dateStr) : null);
        }
        // startTime is now automatic when task moves to IN_PROGRESS (Guatemala time).

        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @GetMapping("/{id}/day-sale-candidates")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getDaySaleCandidates(@PathVariable Long id)
            throws ResourceNotFoundException {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        List<ProductionOrderEntity> daySaleOrders = productionOrderRepository.findActiveOrders().stream()
                .filter(po -> "VENTA_EN_LINEA".equals(po.getOrderType()))
                .filter(po -> !"COMPLETED".equals(po.getStatus()) && !"CANCELLED".equals(po.getStatus()))
                .sorted(Comparator
                        .comparing(ProductionOrderEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductionOrderEntity::getId))
                .collect(Collectors.toList());

        if (daySaleOrders.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Map<Long, ProductionOrderEntity> orderById = daySaleOrders.stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, po -> po, (a, b) -> a, LinkedHashMap::new));
        List<ProductionOrderItemEntity> allItems = daySaleOrders.stream()
                .flatMap(po -> productionOrderItemRepository.findByProductionOrderId(po.getId()).stream())
                .collect(Collectors.toList());

        if (allItems.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<Long> itemIds = allItems.stream().map(ProductionOrderItemEntity::getId).toList();
        List<TaskItemEntity> alreadySelectedAsExtraItems = taskItemRepository
                .findByProductionOrderItemIdInAndDaySaleExtraTrue(itemIds)
                .stream().toList();
        Map<Long, TaskItemEntity> selectedByProductionOrderItemId = alreadySelectedAsExtraItems.stream()
                .filter(it -> it.getProductionOrderItemId() != null)
                .collect(Collectors.toMap(
                        TaskItemEntity::getProductionOrderItemId,
                        it -> it,
                        (a, b) -> a));
        Set<Long> assignedTaskIds = alreadySelectedAsExtraItems.stream()
                .map(TaskItemEntity::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, TaskEntity> assignedTaskById = taskRepository.findAllById(assignedTaskIds).stream()
                .collect(Collectors.toMap(TaskEntity::getId, t -> t, (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductionOrderItemEntity poi : allItems) {
            ProductEntity product = poi.getProductId() != null
                    ? productRepository.findById(poi.getProductId()).orElse(null)
                    : null;
            String colorName = null;
            if (poi.getColorId() != null) {
                ColorEntity color = colorRepository.findById(poi.getColorId()).orElse(null);
                colorName = color != null ? color.getName() : null;
            }

            int qty = calculateItemTotalQuantity(poi);
            double prdTimePerUnit = (product != null && product.getPrdTime() != null && product.getPrdTime() > 0)
                    ? product.getPrdTime()
                    : DEFAULT_PRD_TIME_PER_UNIT;
            double estimatedHours = roundHours(qty * prdTimePerUnit);
            ProductionOrderEntity order = orderById.get(poi.getProductionOrderId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", task.getId());
            row.put("productionOrderId", poi.getProductionOrderId());
            row.put("productionOrderCode", order != null ? order.getCode() : null);
            row.put("productionOrderItemId", poi.getId());
            row.put("productId", poi.getProductId());
            row.put("productCode", product != null ? product.getCode() : null);
            row.put("productName", product != null ? product.getName() : null);
            row.put("colorId", poi.getColorId());
            row.put("colorName", colorName);
            row.put("quantity", qty);
            row.put("estimatedHours", estimatedHours);
            row.put("estimatedMinutes", Math.round(estimatedHours * 60.0));
            row.put("observations", poi.getObservations());
            TaskItemEntity selectedItem = selectedByProductionOrderItemId.get(poi.getId());
            TaskEntity assignedTask = selectedItem != null ? assignedTaskById.get(selectedItem.getTaskId()) : null;
            row.put("assignedTaskId", assignedTask != null ? assignedTask.getId() : null);
            row.put("assignedTaskCode", assignedTask != null ? assignedTask.getCode() : null);
            row.put("assignedDesk", assignedTask != null ? assignedTask.getDesk() : null);
            row.put("assignedScheduledDate", assignedTask != null ? assignedTask.getScheduledDate() : null);
            row.put("assigned", selectedItem != null);
            rows.add(row);
        }

        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> String.valueOf(r.getOrDefault("productionOrderCode", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("productCode", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("colorName", ""))));
        return ResponseEntity.ok(rows);
    }

    @PutMapping("/{id}/day-sale-items")
    @Transactional
    public ResponseEntity<TaskResponse> addDaySaleItemsToTask(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) throws ResourceNotFoundException, BusinessException {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("productionOrderItemIds");
        if (rawIds == null || rawIds.isEmpty()) {
            throw new BusinessException("Seleccione al menos un producto de venta del dia.");
        }

        List<Long> selectedItemIds = rawIds.stream()
                .map(v -> Long.parseLong(v.toString()))
                .distinct()
                .toList();

        List<ProductionOrderItemEntity> selectedItems = productionOrderItemRepository.findAllById(selectedItemIds);
        if (selectedItems.size() != selectedItemIds.size()) {
            throw new BusinessException("Uno o mas productos de venta del dia ya no existen.");
        }

        for (ProductionOrderItemEntity selected : selectedItems) {
            if (taskItemRepository.existsByProductionOrderItemIdAndDaySaleExtraTrue(selected.getId())) {
                throw new BusinessException("El producto " + selected.getId()
                        + " ya fue agregado como extra en otra tarea.");
            }
            ProductionOrderEntity order = productionOrderRepository.findById(selected.getProductionOrderId()).orElse(null);
            if (order == null || !"VENTA_EN_LINEA".equals(order.getOrderType())) {
                throw new BusinessException("Solo se pueden agregar productos de ordenes VENTA_EN_LINEA.");
            }
        }

        for (ProductionOrderItemEntity selected : selectedItems) {
            ProductEntity product = selected.getProductId() != null
                    ? productRepository.findById(selected.getProductId()).orElse(null)
                    : null;
            String colorName = null;
            if (selected.getColorId() != null) {
                ColorEntity color = colorRepository.findById(selected.getColorId()).orElse(null);
                colorName = color != null ? color.getName() : null;
            }

            double prdTimePerUnit = (product != null && product.getPrdTime() != null && product.getPrdTime() > 0)
                    ? product.getPrdTime()
                    : DEFAULT_PRD_TIME_PER_UNIT;
            int qty = calculateItemTotalQuantity(selected);
            double itemHours = roundHours(qty * prdTimePerUnit);
            boolean requiresMaterials = product == null || !Boolean.FALSE.equals(product.getRequiresMaterials());

            taskItemRepository.save(TaskItemEntity.builder()
                    .taskId(task.getId())
                    .productionOrderItemId(selected.getId())
                    .productId(selected.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(selected.getColorId())
                    .colorName(colorName)
                    .quantity(qty)
                    .estimatedHours(itemHours)
                    .observations(selected.getObservations())
                    .leatherDelivered(Boolean.TRUE.equals(task.getLeatherDelivered()))
                    .leatherDeliveredAt(Boolean.TRUE.equals(task.getLeatherDelivered()) ? LocalDateTime.now() : null)
                    .materialsDelivered(!requiresMaterials)
                    .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                    .daySaleExtra(true)
                    .build());
        }

        List<TaskItemEntity> currentItems = taskItemRepository.findByTaskId(task.getId());
        recalculateTaskTotals(task, currentItems);
        task.setMaterialsDelivered(areRequiredTaskItemsDelivered(task));
        task.setMaterialsDeliveredAt(Boolean.TRUE.equals(task.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        TaskEntity updated = taskRepository.save(task);
        return ResponseEntity.ok(toResponse(updated));
    }

    // ==================== DIE-CUT (TROQUELADO) ====================

    @PutMapping("/{id}/die-cut")
    public ResponseEntity<TaskResponse> toggleDieCut(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        boolean ready = body.get("dieCutReady") != null && Boolean.parseBoolean(body.get("dieCutReady").toString());
        if (ready && !Boolean.TRUE.equals(entity.getLeatherDelivered())) {
            // Backward compatibility: if leather movement exists for the PO, sync gate automatically.
            if (hasActiveLeatherDelivery(entity.getProductionOrderId())) {
                entity.setLeatherDelivered(true);
                if (entity.getLeatherDeliveredAt() == null) {
                    entity.setLeatherDeliveredAt(LocalDateTime.now());
                }
            } else {
                throw new BusinessException("No se puede troquelar sin entrega de cuero.");
            }
        }
        if (!ready && (Boolean.TRUE.equals(entity.getMaterialsDelivered())
                || "IN_PROGRESS".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar troquelado porque la tarea ya avanzó de fase.");
        }
        entity.setDieCutReady(ready);
        entity.setDieCutDate(ready ? LocalDate.now() : null);

        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/production-order/{productionOrderId}/die-cut")
    public ResponseEntity<List<TaskResponse>> bulkDieCut(
            @PathVariable Long productionOrderId, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(productionOrderId);
        if (tasks.isEmpty()) {
            throw new ResourceNotFoundException("Tasks for Production Order", productionOrderId);
        }

        boolean ready = body.get("dieCutReady") != null && Boolean.parseBoolean(body.get("dieCutReady").toString());
        LocalDate dieCutDate = ready ? LocalDate.now() : null;

        List<TaskResponse> responses = new ArrayList<>();
        for (TaskEntity task : tasks) {
            if (!"CANCELLED".equals(task.getStatus())) {
                if (ready && !Boolean.TRUE.equals(task.getLeatherDelivered())) {
                    throw new BusinessException("No se puede troquelar masivamente: hay tareas sin entrega de cuero.");
                }
                task.setDieCutReady(ready);
                task.setDieCutDate(dieCutDate);
                responses.add(toResponse(taskRepository.save(task)));
            }
        }
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        TaskEntity entity = taskRepository.findById(id).orElse(null);
        if (entity == null) {
            throw new ResourceNotFoundException("Task", id);
        }
        Long productionOrderId = entity.getProductionOrderId();
        taskItemRepository.deleteByTaskId(id);
        taskRepository.deleteById(id);
        syncProductionOrderStatusFromTasks(productionOrderId);
        return ResponseEntity.noContent().build();
    }

    // ==================== MATERIALS VIEW ====================

    /**
     * Vista para el equipo de materiales: tareas con recetas (BOM) para saber qué despachar.
     * Filtra por fecha programada (por defecto hoy).
     */
    @GetMapping("/materials-view")
    @Transactional(readOnly = true)
    public ResponseEntity<List<MaterialsTaskViewResponse>> getMaterialsView(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = date != null ? date : LocalDate.now();

        List<TaskEntity> tasks = taskRepository.findByScheduledDate(targetDate);
        // Also include tasks without a scheduled date that are pending/in-progress
        if (date == null) {
            List<TaskEntity> unscheduled = taskRepository.findPendingAndInProgressOrdered().stream()
                    .filter(t -> t.getScheduledDate() == null)
                    .toList();
            List<TaskEntity> combined = new ArrayList<>(tasks);
            combined.addAll(unscheduled);
            tasks = combined;
        }

        List<MaterialsTaskViewResponse> responses = tasks.stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .map(this::toMaterialsView)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * Vista materiales para todas las tareas de una orden de producción específica.
     */
    @GetMapping("/materials-view/production-order/{productionOrderId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<MaterialsTaskViewResponse>> getMaterialsViewByOrder(
            @PathVariable Long productionOrderId) {

        List<TaskEntity> tasks = findTasksLinkedToProductionOrder(productionOrderId);
        List<MaterialsTaskViewResponse> responses = tasks.stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .map(this::toMaterialsView)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    private MaterialsTaskViewResponse toMaterialsView(TaskEntity task) {
        ProductionOrderEntity po = task.getProductionOrderId() != null
                ? productionOrderRepository.findById(task.getProductionOrderId()).orElse(null)
                : null;

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(task.getId());

        List<MaterialsTaskViewResponse.TaskProductWithRecipe> products;
        if (!taskItems.isEmpty()) {
            products = taskItems.stream()
                    .map(item -> buildProductWithRecipe(task, item))
                    .collect(Collectors.toList());
        } else if (task.getProductId() != null) {
            products = List.of(buildProductWithRecipe(
                    task,
                    TaskItemEntity.builder()
                            .taskId(task.getId())
                            .productId(task.getProductId())
                            .productCode(task.getProductCode())
                            .productName(task.getProductName())
                            .colorId(task.getColorId())
                            .colorName(task.getColorName())
                            .quantity(task.getQuantity())
                            .leatherDelivered(task.getLeatherDelivered())
                            .leatherDeliveredAt(task.getLeatherDeliveredAt())
                            .materialsDelivered(task.getMaterialsDelivered())
                            .materialsDeliveredAt(task.getMaterialsDeliveredAt())
                            .build()));
        } else {
            products = List.of();
        }

        return MaterialsTaskViewResponse.builder()
                .taskId(task.getId())
                .taskCode(task.getCode())
                .productionOrderCode(task.getProductionOrderCode())
                .productionOrderId(task.getProductionOrderId())
                .orderType(po != null ? po.getOrderType() : null)
                .desk(task.getDesk())
                .scheduledDate(task.getScheduledDate())
                .startTime(task.getStartTime())
                .estimatedHours(task.getEstimatedHours())
                .status(task.getStatus())
                .leatherDelivered(task.getLeatherDelivered())
                .leatherDeliveredAt(task.getLeatherDeliveredAt())
                .dieCutReady(task.getDieCutReady())
                .dieCutDate(task.getDieCutDate())
                .materialsDelivered(areRequiredTaskItemsDelivered(task))
                .materialsDeliveredAt(task.getMaterialsDeliveredAt())
                .requiresMaterials(taskRequiresMaterials(task))
                .workflowStatus(getWorkflowStatus(task))
                .canDeliverMaterials(canDeliverMaterials(task))
                .completedAt(task.getCompletedAt())
                .products(products)
                .build();
    }

    private MaterialsTaskViewResponse.TaskProductWithRecipe buildProductWithRecipe(
            TaskEntity task,
            TaskItemEntity item) {

        List<MaterialsTaskViewResponse.RecipeMaterial> recipe = new ArrayList<>();
        Long productId = item.getProductId();
        String productCode = item.getProductCode();
        String productName = item.getProductName();
        Long colorId = item.getColorId();
        String colorName = item.getColorName();
        Integer quantity = item.getQuantity();

        if (productId != null) {
            // Find active BOM for this product (and optionally color)
            // BOM status is stored as "A" (active)
            List<BomEntity> boms = bomRepository.findByProductIdAndStatus(productId, "A");

            // Prefer BOM matching the specific color, fallback to generic
            BomEntity matchedBom = boms.stream()
                    .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                    .findFirst()
                    .orElse(boms.isEmpty() ? null : boms.get(0));

            if (matchedBom != null) {
                int qty = quantity != null ? quantity : 1;
                List<BomItemEntity> bomItems = bomItemRepository.findByBomId(matchedBom.getId());
                recipe = bomItems.stream()
                        .map(bomItem -> {
                            MaterialEntity material = materialRepository.findById(bomItem.getMaterialId()).orElse(null);
                            BigDecimal totalQty = bomItem.getQuantity() != null
                                    ? bomItem.getQuantity().multiply(BigDecimal.valueOf(qty))
                                    : BigDecimal.ZERO;
                            BigDecimal availableStock = material != null && material.getQuantity() != null
                                    ? material.getQuantity()
                                    : BigDecimal.ZERO;
                            boolean sufficientStock = availableStock.compareTo(totalQty) >= 0;

                            return MaterialsTaskViewResponse.RecipeMaterial.builder()
                                    .materialId(bomItem.getMaterialId())
                                    .materialName(material != null ? material.getName() : null)
                                    .materialSku(material != null ? material.getSku() : null)
                                    .quantityPerUnit(bomItem.getQuantity())
                                    .totalQuantity(totalQty)
                                    .availableStock(availableStock)
                                    .sufficientStock(sufficientStock)
                                    .measurementUnit(bomItem.getMeasurementUnit())
                                    .build();
                        })
                        .collect(Collectors.toList());
            }
        }

        return MaterialsTaskViewResponse.TaskProductWithRecipe.builder()
                .taskItemId(item.getId())
                .productId(productId)
                .productCode(productCode)
                .productName(productName)
                .colorId(colorId)
                .colorName(colorName)
                .quantity(quantity)
                .requiresMaterials(isTaskItemRequiresMaterials(item))
                .leatherDelivered(Boolean.TRUE.equals(item.getLeatherDelivered()) || Boolean.TRUE.equals(task.getLeatherDelivered()))
                .leatherDeliveredAt(item.getLeatherDeliveredAt() != null ? item.getLeatherDeliveredAt() : task.getLeatherDeliveredAt())
                .materialsDelivered(Boolean.TRUE.equals(item.getMaterialsDelivered()) || !isTaskItemRequiresMaterials(item))
                .materialsDeliveredAt(item.getMaterialsDeliveredAt())
                .canDeliverMaterials(canDeliverMaterialsForTaskItem(task, item))
                .recipe(recipe)
                .build();
    }

    // ==================== GENERATE TASKS ====================

    @PostMapping("/generate/{productionOrderId}")
    @Transactional
    public ResponseEntity<List<TaskResponse>> generateTasks(
            @PathVariable Long productionOrderId,
            @RequestParam(name = "force", defaultValue = "false") boolean forceRegenerate)
            throws ResourceNotFoundException, BusinessException {

        List<TaskEntity> generatedTasks = productionTaskGenerationService.generateTasks(
                productionOrderId, forceRegenerate);
        return ResponseEntity.ok(generatedTasks.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    /**
     * Genera tareas directamente para todas las órdenes de producción VENTA_EN_LINEA
     * que aún no tienen tareas generadas. Esto permite tener tareas del plan diario
     * de ventas en línea sin necesitar que existan otras tareas de órdenes regulares.
     *
     * POST /api/tasks/generate-for-pending-online-sales
     */
    @PostMapping("/generate-for-pending-online-sales")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateTasksForPendingOnlineSales()
            throws BusinessException {

        // Buscar todas las OPs de tipo VENTA_EN_LINEA que no estén completadas/canceladas
        List<ProductionOrderEntity> onlineSaleOrders = productionOrderRepository.findActiveOrders()
                .stream()
                .filter(po -> "VENTA_EN_LINEA".equals(po.getOrderType()))
                .filter(po -> !"COMPLETED".equals(po.getStatus()) && !"CANCELLED".equals(po.getStatus()))
                .collect(Collectors.toList());

        if (onlineSaleOrders.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("message", "No hay órdenes de venta en línea pendientes");
            emptyResult.put("tasksGenerated", 0);
            emptyResult.put("ordersProcessed", 0);
            return ResponseEntity.ok(emptyResult);
        }

        // Para cada OP sin tareas, generar tareas
        int totalTasksGenerated = 0;
        int ordersProcessed = 0;
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        for (ProductionOrderEntity po : onlineSaleOrders) {
            // Solo procesar órdenes que no tienen tareas todavía
            List<TaskEntity> existingTasks = taskRepository.findByProductionOrderId(po.getId());
            if (!existingTasks.isEmpty()) {
                continue; // Ya tiene tareas, saltarla
            }

            try {
                List<TaskEntity> generated = productionTaskGenerationService.generateTasks(po.getId(), false);
                totalTasksGenerated += generated.size();
                ordersProcessed++;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("productionOrderCode", po.getCode());
                detail.put("customerName", po.getCustomerName());
                detail.put("tasksCreated", generated.size());
                details.add(detail);
            } catch (Exception e) {
                errors.add(po.getCode() + ": " + e.getMessage());
            }
        }

        String message;
        if (ordersProcessed == 0 && errors.isEmpty()) {
            message = "Todas las órdenes de venta en línea ya tienen tareas generadas";
        } else if (ordersProcessed > 0) {
            message = ordersProcessed + " orden(es) procesada(s), " + totalTasksGenerated + " tarea(s) generada(s)";
        } else {
            message = "No se pudieron generar tareas";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("tasksGenerated", totalTasksGenerated);
        result.put("ordersProcessed", ordersProcessed);
        result.put("details", details);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    private List<TaskEntity> findTasksLinkedToProductionOrder(Long productionOrderId) {
        List<TaskEntity> direct = taskRepository.findByProductionOrderId(productionOrderId);
        List<Long> itemIds = productionOrderItemRepository.findByProductionOrderId(productionOrderId).stream()
                .map(ProductionOrderItemEntity::getId)
                .toList();
        if (itemIds.isEmpty()) return direct;

        List<Long> taskIdsByItems = taskItemRepository.findDistinctTaskIdsByProductionOrderItemIdIn(itemIds);
        Map<Long, TaskEntity> map = new LinkedHashMap<>();
        direct.forEach(t -> map.put(t.getId(), t));
        taskRepository.findAllById(taskIdsByItems).forEach(t -> map.put(t.getId(), t));
        return new ArrayList<>(map.values());
    }

    /**
     * Calculate total quantity for an item (including sizes for CINCHOS type).
     */
    private int calculateItemTotalQuantity(ProductionOrderItemEntity item) {
        int total = 0;
        if (item.getQuantity() != null) {
            total += item.getQuantity();
        }
        if (item.getSizesData() != null && !item.getSizesData().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Integer> sizes = mapper.readValue(item.getSizesData(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
                total += sizes.values().stream().mapToInt(Integer::intValue).sum();
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        return Math.max(total, 1);
    }

    private record DesksCountResolution(int count, String resolvedKey, boolean isDefault) {}

    private DesksCountResolution resolveNumDesks() {
        for (String key : DESKS_COUNT_CONFIG_KEYS) {
            Optional<SystemConfigEntity> config = systemConfigRepository.findByConfigKey(key);
            if (config.isEmpty() || config.get().getConfigValue() == null) continue;

            try {
                int value = Integer.parseInt(config.get().getConfigValue());
                if (value > 0) {
                    return new DesksCountResolution(value, key, false);
                }
            } catch (NumberFormatException ignored) {
                // Try next key
            }
        }

        return new DesksCountResolution(MAX_DESKS, "DEFAULT", true);
    }

    private int getNumDesks() {
        return resolveNumDesks().count();
    }

    private double getTaskBaseHours(TaskEntity task) {
        if (task == null) return 0.0;
        double total = task.getEstimatedHours() != null ? task.getEstimatedHours() : 0.0;
        if (task.getId() == null) return total;
        double extra = taskItemRepository.findByTaskId(task.getId()).stream()
                .filter(item -> Boolean.TRUE.equals(item.getDaySaleExtra()))
                .map(TaskItemEntity::getEstimatedHours)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return Math.max(total - extra, 0.0);
    }

    private int findLeastLoadedDesk(Map<Integer, Double> deskLoads) {
        return deskLoads.entrySet().stream()
                .min(Comparator
                        .comparingDouble((Map.Entry<Integer, Double> e) -> e.getValue() != null ? e.getValue() : 0.0)
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(1);
    }

    private String generateTaskCode() throws BusinessException {
        String documentType = "TASK";
        String series = "TK";

        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> {
                    DocumentSeriesEntity newSeries = DocumentSeriesEntity.builder()
                            .documentType(documentType)
                            .series(series)
                            .currentCorrelative(0L)
                            .status("active")
                            .description("Serie automática para tareas de producción")
                            .build();
                    return documentSeriesRepository.save(newSeries);
                });

        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);

        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }

    private boolean isValidStatus(String status) {
        return "PENDING".equals(status) || "IN_PROGRESS".equals(status)
                || "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean canTransitionStatus(String currentStatus, String newStatus) {
        if (currentStatus == null) return "PENDING".equals(newStatus) || "IN_PROGRESS".equals(newStatus);
        if (currentStatus.equals(newStatus)) return true;
        if ("PENDING".equals(currentStatus)) {
            return "IN_PROGRESS".equals(newStatus) || "CANCELLED".equals(newStatus);
        }
        if ("IN_PROGRESS".equals(currentStatus)) {
            return "COMPLETED".equals(newStatus) || "CANCELLED".equals(newStatus) || "PENDING".equals(newStatus);
        }
        if ("COMPLETED".equals(currentStatus)) {
            return false;
        }
        if ("CANCELLED".equals(currentStatus)) {
            return false;
        }
        return false;
    }

    private boolean canMergeTasks(TaskEntity base, TaskEntity donor) {
        if (base == null || donor == null) return false;
        if (!"PENDING".equals(base.getStatus()) || !"PENDING".equals(donor.getStatus())) return false;
        if (!Objects.equals(base.getDesk(), donor.getDesk())) return false;
        if (!Objects.equals(base.getScheduledDate(), donor.getScheduledDate())) return false;
        if (base.getStartedAt() != null || donor.getStartedAt() != null) return false;
        if (base.getCompletedAt() != null || donor.getCompletedAt() != null) return false;
        if (!Objects.equals(base.getLeatherDelivered(), donor.getLeatherDelivered())) return false;
        if (!Objects.equals(base.getDieCutReady(), donor.getDieCutReady())) return false;
        if (!Objects.equals(base.getMaterialsDelivered(), donor.getMaterialsDelivered())) return false;
        return true;
    }

    private double roundHours(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private LocalDate minDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private boolean hasEnteredTable(TaskEntity entity) {
        return entity.getDesk() != null
                || entity.getScheduledDate() != null
                || (entity.getStartTime() != null && !entity.getStartTime().isBlank());
    }

    private boolean hasStockForMaterialsDelivery(TaskEntity entity) {
        if (entity.getId() == null) return false;
        try {
            ProductionOrderEntity order = entity.getProductionOrderId() != null
                    ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                    : null;
            // Backward compatibility for tasks created before per-task consumption:
            // if the OP was already consumed, allow materials delivery for pending tasks.
            if (order != null && Boolean.TRUE.equals(order.getMaterialsConsumed())) {
                return true;
            }
            List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(entity.getId());
            if (taskItems.isEmpty()) {
                Map<String, Object> validation = materialConsumptionService.validateMaterialAvailabilityForTask(entity.getId());
                Object allAvailable = validation.get("allAvailable");
                return Boolean.TRUE.equals(allAvailable);
            }
            for (TaskItemEntity item : taskItems) {
                if (!isTaskItemRequiresMaterials(item) || Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                    continue;
                }
                Map<String, Object> validation = materialConsumptionService
                        .validateMaterialAvailabilityForTaskItem(entity.getId(), item.getId());
                if (!Boolean.TRUE.equals(validation.get("allAvailable"))) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean canDeliverMaterialsPreconditions(TaskEntity entity) {
        return Boolean.TRUE.equals(entity.getLeatherDelivered())
                && Boolean.TRUE.equals(entity.getDieCutReady())
                && hasEnteredTable(entity);
    }

    private boolean canDeliverMaterials(TaskEntity entity) {
        if (!taskRequiresMaterials(entity)) {
            return canDeliverMaterialsPreconditions(entity);
        }
        return canDeliverMaterialsPreconditions(entity)
                && hasStockForMaterialsDelivery(entity);
    }

    private boolean canDeliverMaterialsForTaskItem(TaskEntity entity, TaskItemEntity item) {
        if (!canDeliverMaterialsPreconditions(entity)) {
            return false;
        }
        if (!isTaskItemRequiresMaterials(item)) {
            return true;
        }
        if (Boolean.TRUE.equals(item.getMaterialsDelivered())) {
            return true;
        }
        try {
            Map<String, Object> validation = materialConsumptionService
                    .validateMaterialAvailabilityForTaskItem(entity.getId(), item.getId());
            return Boolean.TRUE.equals(validation.get("allAvailable"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean canMoveToInProgress(TaskEntity entity) {
        if (!taskRequiresMaterials(entity)) {
            return canDeliverMaterialsPreconditions(entity);
        }
        return canDeliverMaterialsPreconditions(entity) && hasAtLeastOneReadyTaskItem(entity);
    }

    private boolean hasActiveLeatherDelivery(Long productionOrderId) {
        if (productionOrderId == null) return false;
        return leatherMovementRepository.findByProductionOrderIdOrderByCreatedAtDesc(productionOrderId).stream()
                .anyMatch(m -> "SALIDA".equals(m.getMovementType()));
    }

    private String getWorkflowStatus(TaskEntity entity) {
        if ("CANCELLED".equals(entity.getStatus())) return "CANCELLED";
        if (!Boolean.TRUE.equals(entity.getLeatherDelivered())) return "PENDING_LEATHER";
        if (!Boolean.TRUE.equals(entity.getDieCutReady())) return "PENDING_DIE_CUT";
        if (!hasEnteredTable(entity)) return "PENDING_TABLE_ENTRY";
        if (taskRequiresMaterials(entity) && !areRequiredTaskItemsDelivered(entity)) return "PENDING_MATERIAL_DELIVERY";
        if ("COMPLETED".equals(entity.getStatus())) return "COMPLETED";
        if ("IN_PROGRESS".equals(entity.getStatus())) return "IN_PRODUCTION";
        return "READY_TO_START";
    }

    private boolean isTaskItemRequiresMaterials(TaskItemEntity item) {
        if (item == null || item.getProductId() == null) return true;
        ProductEntity product = productRepository.findById(item.getProductId()).orElse(null);
        if (product == null) return true;
        return !Boolean.FALSE.equals(product.getRequiresMaterials());
    }

    private boolean taskRequiresMaterials(TaskEntity entity) {
        if (entity == null) return true;
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(entity.getId());
        if (!items.isEmpty()) {
            return items.stream().anyMatch(this::isTaskItemRequiresMaterials);
        }
        if (entity.getProductId() == null) return true;
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        if (product == null) return true;
        return !Boolean.FALSE.equals(product.getRequiresMaterials());
    }

    private boolean areTaskItemsLeatherDelivered(TaskEntity entity) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(entity.getId());
        if (items.isEmpty()) {
            return Boolean.TRUE.equals(entity.getLeatherDelivered());
        }
        return items.stream().allMatch(item -> Boolean.TRUE.equals(item.getLeatherDelivered()));
    }

    private boolean areRequiredTaskItemsDelivered(TaskEntity entity) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(entity.getId());
        if (items.isEmpty()) {
            return !taskRequiresMaterials(entity) || Boolean.TRUE.equals(entity.getMaterialsDelivered());
        }
        for (TaskItemEntity item : items) {
            if (isTaskItemRequiresMaterials(item)) {
                if (!Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isTaskItemReadyToStart(TaskItemEntity item) {
        if (item == null) return false;
        if (!isTaskItemRequiresMaterials(item)) return true;
        return Boolean.TRUE.equals(item.getMaterialsDelivered());
    }

    private boolean hasAtLeastOneReadyTaskItem(TaskEntity entity) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(entity.getId());
        if (items.isEmpty()) {
            return !taskRequiresMaterials(entity) || Boolean.TRUE.equals(entity.getMaterialsDelivered());
        }
        return items.stream().anyMatch(this::isTaskItemReadyToStart);
    }

    private void syncProductionOrderStatusFromTasks(Long productionOrderId) {
        if (productionOrderId == null) return;
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId).orElse(null);
        if (order == null) return;

        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(productionOrderId);
        if (tasks.isEmpty()) return;

        List<TaskEntity> nonCancelled = tasks.stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .toList();

        String nextStatus;
        if (nonCancelled.isEmpty()) {
            nextStatus = "PENDING";
        } else if (nonCancelled.stream().allMatch(t -> "COMPLETED".equals(t.getStatus()))) {
            nextStatus = "COMPLETED";
        } else if (nonCancelled.stream().anyMatch(t -> "IN_PROGRESS".equals(t.getStatus()))) {
            nextStatus = "IN_PROGRESS";
        } else {
            nextStatus = "PENDING";
        }

        if (!Objects.equals(order.getStatus(), nextStatus)) {
            order.setStatus(nextStatus);
            productionOrderRepository.save(order);
        }
    }

    private void splitBlockedItemsIntoPendingTask(TaskEntity sourceTask) throws BusinessException {
        List<TaskItemEntity> sourceItems = taskItemRepository.findByTaskId(sourceTask.getId());
        if (sourceItems.isEmpty()) {
            return;
        }

        List<TaskItemEntity> readyItems = sourceItems.stream()
                .filter(this::isTaskItemReadyToStart)
                .collect(Collectors.toList());
        List<TaskItemEntity> blockedItems = sourceItems.stream()
                .filter(item -> !isTaskItemReadyToStart(item))
                .collect(Collectors.toList());

        if (blockedItems.isEmpty()) {
            recalculateTaskTotals(sourceTask, readyItems);
            sourceTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(sourceTask));
            sourceTask.setMaterialsDeliveredAt(Boolean.TRUE.equals(sourceTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);
            return;
        }

        if (readyItems.isEmpty()) {
            throw new BusinessException("No hay productos listos para avanzar en esta mesa. Entrega materiales de al menos un producto o mueve la tarea.");
        }

        TaskItemEntity firstBlocked = blockedItems.get(0);
        TaskEntity pendingTask = TaskEntity.builder()
                .code(generateTaskCode())
                .productionOrderId(sourceTask.getProductionOrderId())
                .productionOrderCode(sourceTask.getProductionOrderCode())
                .productionOrderItemId(firstBlocked.getProductionOrderItemId())
                .productId(firstBlocked.getProductId())
                .productCode(firstBlocked.getProductCode())
                .productName(firstBlocked.getProductName())
                .colorId(firstBlocked.getColorId())
                .colorName(firstBlocked.getColorName())
                .observations("Reprogramada por faltante de materiales desde tarea " + sourceTask.getCode())
                .desk(null)
                .scheduledDate(null)
                .startTime(null)
                .deliveryDate(sourceTask.getDeliveryDate())
                .priority(sourceTask.getPriority() != null ? sourceTask.getPriority() + 1 : null)
                .status("PENDING")
                .leatherDelivered(sourceTask.getLeatherDelivered())
                .leatherDeliveredAt(sourceTask.getLeatherDeliveredAt())
                .dieCutReady(sourceTask.getDieCutReady())
                .dieCutDate(sourceTask.getDieCutDate())
                .materialsDelivered(false)
                .materialsDeliveredAt(null)
                .build();
        TaskEntity savedPendingTask = taskRepository.save(pendingTask);

        for (TaskItemEntity blocked : blockedItems) {
            blocked.setTaskId(savedPendingTask.getId());
            blocked.setMaterialsDelivered(false);
            blocked.setMaterialsDeliveredAt(null);
        }
        taskItemRepository.saveAll(blockedItems);

        recalculateTaskTotals(sourceTask, readyItems);
        sourceTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(sourceTask));
        sourceTask.setMaterialsDeliveredAt(Boolean.TRUE.equals(sourceTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);

        recalculateTaskTotals(savedPendingTask, blockedItems);
        savedPendingTask.setMaterialsDelivered(false);
        savedPendingTask.setMaterialsDeliveredAt(null);
        taskRepository.save(savedPendingTask);
    }

    private void recalculateTaskTotals(TaskEntity task, List<TaskItemEntity> items) {
        int totalQty = items.stream()
                .map(TaskItemEntity::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        double totalHours = items.stream()
                .map(TaskItemEntity::getEstimatedHours)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        if (totalQty <= 0) {
            totalQty = 1;
        }

        task.setQuantity(totalQty);
        task.setEstimatedHours(roundHours(totalHours));

        TaskItemEntity primary = items.isEmpty() ? null : items.get(0);
        if (primary != null) {
            task.setProductionOrderItemId(primary.getProductionOrderItemId());
            task.setProductId(primary.getProductId());
            task.setProductCode(primary.getProductCode());
            task.setProductName(primary.getProductName());
            task.setColorId(primary.getColorId());
            task.setColorName(primary.getColorName());
        }
    }

    // ==================== TICKET BUILDER ====================

    private TaskTicketResponse buildTicket(TaskEntity task) {
        ProductionOrderEntity po = task.getProductionOrderId() != null
                ? productionOrderRepository.findById(task.getProductionOrderId()).orElse(null)
                : null;

        // Build items list from task_item table, fallback to task's own product fields
        List<TaskItemEntity> itemEntities = taskItemRepository.findByTaskId(task.getId());
        List<TaskTicketResponse.TicketItem> ticketItems = new ArrayList<>();

        if (!itemEntities.isEmpty()) {
            for (TaskItemEntity item : itemEntities) {
                ticketItems.add(TaskTicketResponse.TicketItem.builder()
                        .productId(item.getProductId())
                        .productCode(item.getProductCode())
                        .productName(item.getProductName())
                        .colorId(item.getColorId())
                        .colorName(item.getColorName())
                        .quantity(item.getQuantity())
                        .estimatedHours(item.getEstimatedHours())
                        .observations(item.getObservations())
                        .daySaleExtra(Boolean.TRUE.equals(item.getDaySaleExtra()))
                        .build());
            }
        } else if (task.getProductId() != null) {
            // Legacy: single-product task
            ticketItems.add(TaskTicketResponse.TicketItem.builder()
                    .productId(task.getProductId())
                    .productCode(task.getProductCode())
                    .productName(task.getProductName())
                    .colorId(task.getColorId())
                    .colorName(task.getColorName())
                    .quantity(task.getQuantity())
                    .estimatedHours(task.getEstimatedHours())
                    .observations(task.getObservations())
                    .daySaleExtra(false)
                    .build());
        }

        return TaskTicketResponse.builder()
                .taskId(task.getId())
                .taskCode(task.getCode())
                .desk(task.getDesk())
                .scheduledDate(task.getScheduledDate())
                .startTime(task.getStartTime())
                .estimatedHours(task.getEstimatedHours())
                .status(task.getStatus())
                .completedAt(task.getCompletedAt())
                .dieCutReady(task.getDieCutReady())
                .productionOrderCode(task.getProductionOrderCode())
                .deliveryDate(task.getDeliveryDate())
                .orderObservations(po != null ? po.getObservations() : null)
                .items(ticketItems)
                .build();
    }

    // ==================== MAPPING ====================

    private TaskResponse toResponse(TaskEntity entity) {
        // Load items
        List<TaskItemEntity> itemEntities = taskItemRepository.findByTaskId(entity.getId());
        List<TaskResponse.TaskItemDTO> itemDTOs = itemEntities.stream()
                .map(item -> TaskResponse.TaskItemDTO.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productCode(item.getProductCode())
                        .productName(item.getProductName())
                        .colorId(item.getColorId())
                        .colorName(item.getColorName())
                        .quantity(item.getQuantity())
                        .estimatedHours(item.getEstimatedHours())
                        .observations(item.getObservations())
                        .requiresMaterials(isTaskItemRequiresMaterials(item))
                        .leatherDelivered(Boolean.TRUE.equals(item.getLeatherDelivered()) || Boolean.TRUE.equals(entity.getLeatherDelivered()))
                        .leatherDeliveredAt(item.getLeatherDeliveredAt() != null ? item.getLeatherDeliveredAt() : entity.getLeatherDeliveredAt())
                        .materialsDelivered(Boolean.TRUE.equals(item.getMaterialsDelivered()) || !isTaskItemRequiresMaterials(item))
                        .materialsDeliveredAt(item.getMaterialsDeliveredAt())
                        .daySaleExtra(Boolean.TRUE.equals(item.getDaySaleExtra()))
                        .build())
                .collect(Collectors.toList());

        return TaskResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .productionOrderId(entity.getProductionOrderId())
                .productionOrderCode(entity.getProductionOrderCode())
                .productionOrderItemId(entity.getProductionOrderItemId())
                .productId(entity.getProductId())
                .productName(entity.getProductName())
                .productCode(entity.getProductCode())
                .colorId(entity.getColorId())
                .colorName(entity.getColorName())
                .quantity(entity.getQuantity())
                .observations(entity.getObservations())
                .desk(entity.getDesk())
                .estimatedHours(entity.getEstimatedHours())
                .scheduledDate(entity.getScheduledDate())
                .deliveryDate(entity.getDeliveryDate())
                .priority(entity.getPriority())
                .startTime(entity.getStartTime())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .actualDurationMinutes(entity.getActualDurationMinutes())
                .wasteQuantity(entity.getWasteQuantity())
                .wasteNotes(entity.getWasteNotes())
                .leatherDelivered(entity.getLeatherDelivered())
                .leatherDeliveredAt(entity.getLeatherDeliveredAt())
                .dieCutReady(entity.getDieCutReady())
                .dieCutDate(entity.getDieCutDate())
                .materialsDelivered(areRequiredTaskItemsDelivered(entity))
                .materialsDeliveredAt(entity.getMaterialsDeliveredAt())
                .requiresMaterials(taskRequiresMaterials(entity))
                .workflowStatus(getWorkflowStatus(entity))
                .canDeliverMaterials(canDeliverMaterials(entity))
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .items(itemDTOs)
                .build();
    }

    // ==================== INNER CLASSES ====================

    private record DeskDateKey(LocalDate date, Integer desk) {}
}

