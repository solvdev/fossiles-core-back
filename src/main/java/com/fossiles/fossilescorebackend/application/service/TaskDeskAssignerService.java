package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.DeskSlotFinder;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Da mesa a una tarea ya troquelada.
 *
 * <p>El autoplan crea las tareas sin mesa: a mesa solo baja lo cortado. Antes nadie cerraba
 * ese paso al marcar el corte, y la tarea quedaba "lista" sin mesa hasta que alguien pulsara
 * Distribuir o se liberara una mesa. Ahora se llama al terminar el troquelado y desde el botón
 * de asignar del Centro, con una sola regla: el día de la tarea (hoy si no tiene o ya pasó),
 * la mesa con menos carga que tenga cupo, y al azar entre las empatadas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDeskAssignerService {

    private final TaskRepository taskRepository;
    private final ProductionDeskCountService productionDeskCountService;
    private final TaskDeskHoursService taskDeskHoursService;
    private final ProductionPlanningLock productionPlanningLock;

    /**
     * Intento silencioso tras marcar el corte. Si la tarea no está lista o el día no tiene
     * cupo, se queda sin mesa (la toma el relleno cuando una mesa se libere) y no se avisa error.
     */
    @Transactional
    public Optional<TaskEntity> assignIfReady(Long taskId) {
        try {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !isAssignable(task)) {
                return Optional.empty();
            }
            return Optional.ofNullable(place(task));
        } catch (BusinessException e) {
            log.warn("Asignación automática de mesa para tarea {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Botón "Asignar mesa" del Centro: misma regla, pero explica por qué no se pudo. */
    @Transactional
    public TaskEntity assignOrExplain(Long taskId) throws BusinessException, ResourceNotFoundException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        if (task.getDesk() != null) {
            throw new BusinessException("La tarea " + task.getCode() + " ya está en la mesa " + task.getDesk() + ".");
        }
        if (!"PENDING".equals(task.getStatus())) {
            throw new BusinessException("Solo las tareas pendientes se asignan a mesa.");
        }
        if (!Boolean.TRUE.equals(task.getDieCutReady())) {
            throw new BusinessException("La tarea " + task.getCode()
                    + " tiene producto sin troquelar. Marque el corte en «Por troquelar» y la mesa se asigna sola.");
        }
        TaskEntity placed = place(task);
        if (placed == null) {
            throw new BusinessException("No hay mesa con cupo libre el " + targetDate(task)
                    + ". La tarea queda lista y entra a la primera mesa que se libere.");
        }
        return placed;
    }

    private static boolean isAssignable(TaskEntity task) {
        return task.getDesk() == null
                && "PENDING".equals(task.getStatus())
                && Boolean.TRUE.equals(task.getDieCutReady());
    }

    private static LocalDate targetDate(TaskEntity task) {
        LocalDate today = GuatemalaDateTime.today();
        LocalDate date = task.getScheduledDate();
        if (date == null || date.isBefore(today)) {
            date = today;
        }
        return DeskSlotFinder.nextWorkday(date);
    }

    private TaskEntity place(TaskEntity task) throws BusinessException {
        productionPlanningLock.acquire();
        LocalDate date = targetDate(task);
        int numDesks = productionDeskCountService.getDay(date).getNumDesks();

        List<TaskEntity> sameDay = taskRepository.findByScheduledDate(date).stream()
                .filter(t -> t.getDesk() != null)
                .filter(t -> !"CANCELLED".equals(t.getStatus()) && !"COMPLETED".equals(t.getStatus()))
                .filter(t -> !Objects.equals(t.getId(), task.getId()))
                .toList();
        Map<Long, Double> extra = taskDeskHoursService.daySaleExtraByTaskId(
                sameDay.stream().map(TaskEntity::getId).toList());
        Map<Integer, Double> loads = new HashMap<>();
        for (TaskEntity t : sameDay) {
            loads.merge(t.getDesk(), taskDeskHoursService.baseHours(t, extra), Double::sum);
        }

        Integer desk = DeskSlotFinder.pickDeskOnDay(loads, numDesks, taskDeskHoursService.baseHours(task));
        if (desk == null) {
            return null;
        }
        task.setDesk(desk);
        task.setScheduledDate(date);
        return taskRepository.save(task);
    }
}
