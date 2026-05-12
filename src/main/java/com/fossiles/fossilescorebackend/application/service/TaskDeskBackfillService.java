package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Al completarse una tarea y liberarse una mesa, asigna a esa mesa (mismo día) las siguientes
 * tareas PENDING sin mesa, siguiendo la cola por OP (schedulingPriority + FIFO createdAt),
 * respetando 4h por mesa/día con excepción OPL (VENTA_EN_LINEA) en el día ancla.
 */
@Service
@RequiredArgsConstructor
public class TaskDeskBackfillService {

    private static final double MAX_HOURS_PER_DESK_PER_DAY = 4.0;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;

    /**
     * @param freedDesk     mesa que queda libre (1..maxDesks)
     * @param anchorDate    fecha de trabajo donde aplica la capacidad
     * @param maxConfigured número de mesas activas (tasks fuera de rango ignoran assign)
     */
    @Transactional
    public void backfillFreedDeskAfterCompletion(Integer freedDesk, LocalDate anchorDate, int maxConfigured) {
        if (freedDesk == null || anchorDate == null || maxConfigured < 1) {
            return;
        }
        if (freedDesk < 1 || freedDesk > maxConfigured) {
            return;
        }

        List<TaskEntity> pool = taskRepository.findPendingAndInProgressOrdered();

        List<TaskEntity> candidates = pool.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> t.getDesk() == null)
                .filter(t -> Boolean.TRUE.equals(t.getDieCutReady()))
                .filter(t -> {
                    LocalDate sd = t.getScheduledDate();
                    return sd == null || sd.equals(anchorDate);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        if (candidates.isEmpty()) {
            return;
        }

        Map<Long, List<TaskEntity>> byPo = candidates.stream()
                .filter(t -> t.getProductionOrderId() != null)
                .collect(Collectors.groupingBy(TaskEntity::getProductionOrderId));
        List<TaskEntity> withoutPo = candidates.stream()
                .filter(t -> t.getProductionOrderId() == null)
                .collect(Collectors.toCollection(ArrayList::new));

        Set<Long> poIds = new HashSet<>(byPo.keySet());

        List<ProductionOrderEntity> pos = poIds.isEmpty() ? List.of() : productionOrderRepository.findAllById(poIds);
        Map<Long, Integer> prioByPo = new HashMap<>();
        Map<Long, LocalDateTime> createdAtByPo = new HashMap<>();
        Map<Long, Boolean> isOplByPo = new HashMap<>();
        for (ProductionOrderEntity po : pos) {
            Long id = po.getId();
            if (id == null) continue;
            prioByPo.put(id, Optional.ofNullable(po.getSchedulingPriority()).orElse(Integer.MAX_VALUE));
            createdAtByPo.put(id, po.getCreatedAt() != null ? po.getCreatedAt() : LocalDateTime.MAX);
            isOplByPo.put(id, "VENTA_EN_LINEA".equalsIgnoreCase(String.valueOf(po.getOrderType() == null ? "" : po.getOrderType()).trim()));
        }
        for (Long id : poIds) {
            prioByPo.putIfAbsent(id, Integer.MAX_VALUE);
            createdAtByPo.putIfAbsent(id, LocalDateTime.MAX);
            isOplByPo.putIfAbsent(id, false);
        }

        Comparator<Long> opQueueComparator = Comparator
                .comparing((Long poId) -> prioByPo.getOrDefault(poId, Integer.MAX_VALUE))
                .thenComparing(poId -> createdAtByPo.getOrDefault(poId, LocalDateTime.MAX), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(poId -> poId != null ? poId : Long.MAX_VALUE);

        Comparator<TaskEntity> withinPoComparator = Comparator
                .comparing((TaskEntity t) -> -getTaskBaseHours(t))
                .thenComparing(TaskEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getId);

        for (Map.Entry<Long, List<TaskEntity>> e : byPo.entrySet()) {
            e.getValue().sort(withinPoComparator);
        }
        withoutPo.sort(withinPoComparator);

        double load = sumHoursAssignedToDeskDay(pool, freedDesk, anchorDate);

        List<Long> opQueue = byPo.keySet().stream()
                .sorted(opQueueComparator)
                .collect(Collectors.toList());

        List<TaskEntity> toPersist = new ArrayList<>();
        // Greedy por OP: siempre intentar desde la OP más prioritaria disponible.
        while (true) {
            boolean assigned = false;

            for (Long poId : opQueue) {
                List<TaskEntity> list = byPo.get(poId);
                if (list == null || list.isEmpty()) continue;

                boolean isOpl = Boolean.TRUE.equals(isOplByPo.get(poId));

                for (int i = 0; i < list.size(); i++) {
                    TaskEntity cand = list.get(i);
                    double h = getTaskBaseHours(cand);
                    boolean oversizedSingle = h > MAX_HOURS_PER_DESK_PER_DAY + 1e-9;

                    boolean canOvercap = isOpl; // anchorDate ya es el día ancla del evento
                    boolean fits = canOvercap || (oversizedSingle && load <= 1e-9) || (load + h <= MAX_HOURS_PER_DESK_PER_DAY + 1e-9);
                    if (!fits) {
                        continue;
                    }

                    cand.setDesk(freedDesk);
                    cand.setScheduledDate(anchorDate);
                    toPersist.add(cand);
                    load += h;

                    list.remove(i);
                    assigned = true;
                    break;
                }

                if (assigned) {
                    break;
                }
            }

            if (!assigned) {
                // Como fallback, intentar tareas sin productionOrderId (van al final y nunca pueden sobrepasar tope).
                for (int i = 0; i < withoutPo.size(); i++) {
                    TaskEntity cand = withoutPo.get(i);
                    double h = getTaskBaseHours(cand);
                    boolean oversizedSingle = h > MAX_HOURS_PER_DESK_PER_DAY + 1e-9;
                    boolean fits = (oversizedSingle && load <= 1e-9) || (load + h <= MAX_HOURS_PER_DESK_PER_DAY + 1e-9);
                    if (!fits) continue;

                    cand.setDesk(freedDesk);
                    cand.setScheduledDate(anchorDate);
                    toPersist.add(cand);
                    load += h;
                    withoutPo.remove(i);
                    assigned = true;
                    break;
                }
            }

            if (!assigned) break;
        }

        if (!toPersist.isEmpty()) {
            taskRepository.saveAll(toPersist);
        }
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

    private double sumHoursAssignedToDeskDay(List<TaskEntity> pool, int desk, LocalDate date) {
        return pool.stream()
                .filter(t -> "PENDING".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus()))
                .filter(t -> t.getDesk() != null && t.getDesk().equals(desk))
                .filter(t -> Objects.equals(t.getScheduledDate(), date))
                .mapToDouble(this::getTaskBaseHours)
                .sum();
    }
}
