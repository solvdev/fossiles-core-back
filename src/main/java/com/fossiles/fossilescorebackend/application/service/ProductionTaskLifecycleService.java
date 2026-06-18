package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
        return unitRepository.countByProductionOrderId(task.getProductionOrderId()) > 0;
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

        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(productionOrderId);
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
            long minutes = Duration.between(task.getStartedAt(), task.getCompletedAt()).toMinutes();
            task.setActualDurationMinutes((int) Math.max(0, minutes));
        }
        taskRepository.save(task);
    }
}
