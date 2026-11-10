package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductionTaskLifecycleService {

    public static final String STATUS_AWAITING_WAREHOUSE = "AWAITING_WAREHOUSE";

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderWarehouseUnitRepository unitRepository;

    public boolean isAwaitingWarehouseStatus(String status) {
        return STATUS_AWAITING_WAREHOUSE.equals(status);
    }

    public boolean shouldDeferCompletionToWarehouse(TaskEntity task) {
        if (task == null || task.getProductionOrderId() == null) {
            return false;
        }
        if (isCinchoMaterialsTask(task)) {
            return false;
        }
        // Una tarea consolidada espera a bodega si cualquiera de sus OPV ya lleva unidades.
        return ordersOfTask(task).stream().anyMatch(id -> unitRepository.countByProductionOrderId(id) > 0);
    }

    /** OP base de la tarea y las OP de sus líneas propias (más de una si es consolidada). */
    public Set<Long> ordersOfTask(TaskEntity task) {
        Set<Long> ids = new LinkedHashSet<>();
        if (task == null) {
            return ids;
        }
        if (task.getProductionOrderId() != null) {
            ids.add(task.getProductionOrderId());
        }
        if (task.getId() != null) {
            ids.addAll(taskItemRepository.findBaseLineOrderIdsByTaskId(task.getId()));
        }
        return ids;
    }

    /** Sincroniza el estado de todas las OP que trabaja la tarea. */
    @Transactional
    public void syncProductionOrderStatusesOfTask(TaskEntity task) {
        for (Long orderId : ordersOfTask(task)) {
            syncProductionOrderStatusFromTasks(orderId);
        }
    }

    private boolean isCinchoMaterialsTask(TaskEntity task) {
        String obs = task.getObservations();
        return obs != null && obs.toLowerCase().contains("materiales cincho");
    }

    @Transactional
    public void syncProductionOrderStatusFromTasks(Long productionOrderId) {
        if (productionOrderId == null) {
            return;
        }
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId).orElse(null);
        if (order == null) {
            return;
        }

        Map<Long, TaskEntity> linked = new LinkedHashMap<>();
        taskRepository.findByProductionOrderId(productionOrderId).forEach(t -> linked.put(t.getId(), t));
        // Tareas consolidadas cuya OP base es otra OPV de Luis Felipe.
        List<Long> viaItems = taskItemRepository.findBaseLineTaskIdsByProductionOrderId(productionOrderId).stream()
                .filter(id -> !linked.containsKey(id))
                .toList();
        if (!viaItems.isEmpty()) {
            taskRepository.findAllById(viaItems).forEach(t -> linked.put(t.getId(), t));
        }
        List<TaskEntity> tasks = new ArrayList<>(linked.values());
        if (tasks.isEmpty()) {
            return;
        }

        List<TaskEntity> nonCancelled = tasks.stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .toList();

        String nextStatus;
        if (nonCancelled.isEmpty()) {
            nextStatus = "PENDING";
        } else if (nonCancelled.stream().allMatch(t -> "COMPLETED".equals(t.getStatus()))) {
            nextStatus = "COMPLETED";
        } else if (nonCancelled.stream().anyMatch(t ->
                "IN_PROGRESS".equals(t.getStatus()) || STATUS_AWAITING_WAREHOUSE.equals(t.getStatus()))) {
            nextStatus = "IN_PROGRESS";
        } else {
            nextStatus = "PENDING";
        }

        if (!Objects.equals(order.getStatus(), nextStatus)) {
            order.setStatus(nextStatus);
            productionOrderRepository.save(order);
        }
    }

    @Transactional
    public void tryCompleteTasksAfterWarehouseReceipt(
            Long productionOrderId,
            Long productionOrderItemId,
            LocalDateTime receivedAt
    ) {
        if (productionOrderId == null || productionOrderItemId == null) {
            return;
        }
        List<Long> taskIds = taskItemRepository.findDistinctTaskIdsByProductionOrderItemIdIn(
                List.of(productionOrderItemId));
        if (taskIds.isEmpty()) {
            return;
        }
        LocalDateTime completionTime = receivedAt != null ? receivedAt : LocalDateTime.now();
        for (Long taskId : taskIds) {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !STATUS_AWAITING_WAREHOUSE.equals(task.getStatus())) {
                continue;
            }
            if (!allTaskItemsReceivedInWarehouse(task)) {
                continue;
            }
            completeTaskAtWarehouse(task, completionTime);
            syncProductionOrderStatusesOfTask(task);
        }
        syncProductionOrderStatusFromTasks(productionOrderId);
    }

    private boolean allTaskItemsReceivedInWarehouse(TaskEntity task) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(task.getId());
        if (items.isEmpty()) {
            Long poiId = task.getProductionOrderItemId();
            return poiId != null && !hasPendingWarehouseUnits(poiId);
        }
        for (TaskItemEntity item : items) {
            Long poiId = item.getProductionOrderItemId();
            if (poiId == null) {
                continue;
            }
            if (hasPendingWarehouseUnits(poiId)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPendingWarehouseUnits(Long productionOrderItemId) {
        List<ProductionOrderWarehouseUnitEntity> units = unitRepository
                .findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(productionOrderItemId);
        if (units.isEmpty()) {
            return true;
        }
        return units.stream().anyMatch(u ->
                ProductionOrderWarehouseUnitService.STATUS_PENDING.equals(
                        normalizeReceiptStatus(u.getReceiptStatus())));
    }

    private static String normalizeReceiptStatus(String status) {
        if (status == null || status.isBlank()) {
            return ProductionOrderWarehouseUnitService.STATUS_PENDING;
        }
        return status.trim().toUpperCase();
    }

    private void completeTaskAtWarehouse(TaskEntity task, LocalDateTime completedAt) {
        task.setStatus("COMPLETED");
        if (task.getCompletedAt() == null) {
            task.setCompletedAt(completedAt);
        }
        if (task.getStartedAt() != null && task.getCompletedAt() != null) {
            // Tiempo realmente trabajado: solo lo que cae dentro de la jornada.
            long minutes = ProductionShift.workingMinutesBetween(
                    task.getStartedAt(), task.getCompletedAt());
            task.setActualDurationMinutes((int) Math.max(0, minutes));
        }
        taskRepository.save(task);
    }
}
