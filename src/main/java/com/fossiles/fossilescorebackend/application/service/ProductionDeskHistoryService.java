package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.DeskHistoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SpecialDeskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskSupervisorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionDeskSupervisorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Historial de mesas: por cada mesa, las tareas que pasaron por ella, de qué orden eran y cuánto
 * tardaron. Se arma con lo que ya guarda la tarea ({@code desk} mientras está en mesa y
 * {@code worked_desk} al completarla); no hay tabla nueva.
 *
 * <p>Límite conocido: si una tarea se movió entre mesas antes de terminar, solo queda la última,
 * porque el movimiento no se registra en {@code task_event}.
 */
@Service
@RequiredArgsConstructor
public class ProductionDeskHistoryService {

    /** Tope del rango: más allá la respuesta crece sin que la pantalla lo pueda leer. */
    public static final int MAX_RANGE_DAYS = 93;

    private static final int ITEM_CHUNK = 1000;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionDeskSupervisorRepository supervisorRepository;
    private final ProductionDeskCountService productionDeskCountService;
    private final TaskWorkflowService taskWorkflowService;

    @Transactional(readOnly = true)
    public DeskHistoryResponse history(LocalDate from, LocalDate to) throws BusinessException {
        if (from == null || to == null) {
            throw new BusinessException("Indique las fechas «desde» y «hasta».");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("La fecha «hasta» no puede ser anterior a «desde».");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException("El rango máximo es de " + MAX_RANGE_DAYS + " días.");
        }

        List<TaskEntity> candidates = taskRepository.findDeskHistoryCandidates(
                from, to, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        // Día al que cuenta cada tarea; fuera de la ventana se descarta.
        Map<Long, LocalDate> dayByTask = new HashMap<>();
        List<TaskEntity> inRange = new ArrayList<>();
        for (TaskEntity t : candidates) {
            LocalDate day = dayOf(t);
            if (day == null || day.isBefore(from) || day.isAfter(to) || deskOf(t) == null) {
                continue;
            }
            dayByTask.put(t.getId(), day);
            inRange.add(t);
        }

        Map<Long, List<TaskItemEntity>> itemsByTask = loadItems(inRange);
        SupervisorTimeline supervisors = new SupervisorTimeline(supervisorRepository.findAll());
        LocalDateTime now = GuatemalaDateTime.now();

        List<DeskHistoryResponse.Entry> entries = new ArrayList<>();
        Set<Integer> seenDesks = new TreeSet<>();
        for (TaskEntity t : inRange) {
            int desk = deskOf(t);
            seenDesks.add(desk);
            entries.add(toEntry(t, desk, dayByTask.get(t.getId()),
                    itemsByTask.getOrDefault(t.getId(), List.of()), supervisors, now));
        }
        entries.sort(Comparator
                .comparing(DeskHistoryResponse.Entry::getDay).reversed()
                .thenComparing(DeskHistoryResponse.Entry::getDesk)
                .thenComparing(e -> e.getStartedAt() != null ? e.getStartedAt() : LocalDateTime.MAX)
                .thenComparing(DeskHistoryResponse.Entry::getTaskId));

        return DeskHistoryResponse.builder()
                .from(from)
                .to(to)
                .desks(buildDesks(to, seenDesks, supervisors))
                .tasks(entries)
                .build();
    }

    // ==================== mesas del rango ====================

    private List<DeskHistoryResponse.DeskInfo> buildDesks(LocalDate to, Set<Integer> seenDesks,
                                                          SupervisorTimeline supervisors) {
        Map<Integer, SpecialDeskResponse> special = new HashMap<>();
        for (SpecialDeskResponse s : productionDeskCountService.activeSpecialDesks()) {
            special.put(s.getDesk(), s);
        }
        Set<Integer> all = new TreeSet<>(seenDesks);
        try {
            int plant = productionDeskCountService.getDay(to).getNumDesks();
            for (int d = 1; d <= plant; d++) {
                all.add(d);
            }
        } catch (BusinessException ignored) {
            // sin cantidad configurada: quedan las mesas que aparecen en el historial
        }
        all.addAll(special.keySet());

        List<DeskHistoryResponse.DeskInfo> out = new ArrayList<>();
        for (Integer d : all) {
            SpecialDeskResponse sp = special.get(d);
            out.add(DeskHistoryResponse.DeskInfo.builder()
                    .desk(d)
                    .special(sp != null)
                    .name(sp != null ? sp.getName() : null)
                    .supervisorName(supervisors.nameFor(d, to))
                    .build());
        }
        return out;
    }

    // ==================== una tarea ====================

    private DeskHistoryResponse.Entry toEntry(TaskEntity t, int desk, LocalDate day, List<TaskItemEntity> items,
                                              SupervisorTimeline supervisors, LocalDateTime now) {
        List<DeskHistoryResponse.Item> itemDtos = new ArrayList<>();
        int pieces = 0;
        for (TaskItemEntity it : items) {
            int q = it.getQuantity() != null ? it.getQuantity() : 0;
            pieces += q;
            itemDtos.add(DeskHistoryResponse.Item.builder()
                    .productCode(it.getProductCode())
                    .productName(it.getProductName())
                    .colorName(it.getColorName())
                    .quantity(q)
                    .build());
        }
        if (items.isEmpty()) {
            // Tarea de un solo producto, anterior a task_item.
            pieces = t.getQuantity() != null ? t.getQuantity() : 0;
            if (t.getProductName() != null || t.getProductCode() != null) {
                itemDtos.add(DeskHistoryResponse.Item.builder()
                        .productCode(t.getProductCode())
                        .productName(t.getProductName())
                        .colorName(t.getColorName())
                        .quantity(pieces)
                        .build());
            }
        }

        return DeskHistoryResponse.Entry.builder()
                .taskId(t.getId())
                .code(t.getCode())
                .desk(desk)
                .day(day)
                .supervisorName(supervisors.nameFor(desk, day))
                .productionOrderId(t.getProductionOrderId())
                .productionOrderCode(t.getProductionOrderCode())
                .status(t.getStatus())
                .scheduledDate(t.getScheduledDate())
                .startedAt(t.getStartedAt())
                .completedAt(t.getCompletedAt())
                .paused(t.getPausedAt() != null && "IN_PROGRESS".equals(t.getStatus()))
                .workedMinutes(workedMinutes(t, now))
                .expectedMinutes(expectedMinutes(t))
                .delayMinutes(t.getDelayMinutes())
                .delayed(Boolean.TRUE.equals(t.getDelayed()))
                .delayForgiven(t.getDelayForgivenAt() != null)
                .pieces(pieces)
                .wasteQuantity(t.getWasteQuantity() != null ? t.getWasteQuantity() : 0)
                .items(itemDtos)
                .build();
    }

    /** La mesa que cuenta: la actual si sigue en una; si no, donde se trabajó al completarla. */
    static Integer deskOf(TaskEntity t) {
        return t.getDesk() != null ? t.getDesk() : t.getWorkedDesk();
    }

    /** Día al que cuenta la tarea: el del cierre; si no cerró, el programado; si no, el de inicio. */
    static LocalDate dayOf(TaskEntity t) {
        if (t.getCompletedAt() != null) {
            return t.getCompletedAt().toLocalDate();
        }
        if (t.getScheduledDate() != null) {
            return t.getScheduledDate();
        }
        return t.getStartedAt() != null ? t.getStartedAt().toLocalDate() : null;
    }

    private Integer workedMinutes(TaskEntity t, LocalDateTime now) {
        if (t.getStartedAt() == null) {
            return null;
        }
        if (t.getCompletedAt() != null) {
            if (t.getActualDurationMinutes() != null) {
                return t.getActualDurationMinutes();
            }
            return (int) Math.max(0, ProductionShift.workingMinutesBetween(t.getStartedAt(), t.getCompletedAt()));
        }
        if ("IN_PROGRESS".equals(t.getStatus())) {
            // Todavía corriendo: descuenta pausas con el historial de la tarea.
            return taskWorkflowService.workedMinutes(t, now);
        }
        return null;
    }

    private static Integer expectedMinutes(TaskEntity t) {
        if (t.getExpectedMinutes() != null) {
            return t.getExpectedMinutes();
        }
        return t.getEstimatedHours() != null ? (int) Math.round(t.getEstimatedHours() * 60) : null;
    }

    private Map<Long, List<TaskItemEntity>> loadItems(List<TaskEntity> tasks) {
        Map<Long, List<TaskItemEntity>> out = new HashMap<>();
        List<Long> ids = tasks.stream().map(TaskEntity::getId).toList();
        for (int i = 0; i < ids.size(); i += ITEM_CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + ITEM_CHUNK));
            for (TaskItemEntity it : taskItemRepository.findByTaskIdIn(chunk)) {
                out.computeIfAbsent(it.getTaskId(), k -> new ArrayList<>()).add(it);
            }
        }
        return out;
    }

    /** Encargado de cada mesa según la fecha: último registro con fecha efectiva menor o igual. */
    static final class SupervisorTimeline {
        private final Map<Integer, List<ProductionDeskSupervisorEntity>> byDesk = new HashMap<>();

        SupervisorTimeline(List<ProductionDeskSupervisorEntity> rows) {
            for (ProductionDeskSupervisorEntity r : rows) {
                if (r.getDesk() == null || r.getEffectiveDate() == null) {
                    continue;
                }
                byDesk.computeIfAbsent(r.getDesk(), k -> new ArrayList<>()).add(r);
            }
            byDesk.values().forEach(list -> list.sort(Comparator.comparing(
                    ProductionDeskSupervisorEntity::getEffectiveDate).reversed()));
        }

        String nameFor(int desk, LocalDate asOf) {
            for (ProductionDeskSupervisorEntity r : byDesk.getOrDefault(desk, List.of())) {
                if (!r.getEffectiveDate().isAfter(asOf)) {
                    String name = r.getSupervisorName() == null ? "" : r.getSupervisorName().trim();
                    return name.isEmpty() ? null : name;
                }
            }
            return null;
        }
    }
}
