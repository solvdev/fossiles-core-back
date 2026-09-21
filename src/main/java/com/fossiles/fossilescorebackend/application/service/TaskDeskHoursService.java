package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cuántas horas de mesa pesa una tarea.
 *
 * <p>Este cálculo estaba copiado palabra por palabra en tres sitios
 * ({@code TaskController}, {@code TaskDeskBackfillService} y
 * {@code ProductionTaskGenerationService}), y un cuarto lo hacía distinto
 * ({@code ProductionAutoPlannerService}, que usaba {@code estimatedHours} crudo sin
 * descontar los ítems de venta del día). El mismo día podía verse con 3,5 h en una
 * pantalla y 4,2 h en otra quince minutos después.
 */
@Service
@RequiredArgsConstructor
public class TaskDeskHoursService {

    /**
     * Horas que se le siguen reservando a la mesa a una tarea ya empezada, por poco que
     * le quede. Existe porque el descuento se calcula con el reloj y no con trabajo
     * reportado: sin piso, una tarea abierta desde hace semanas pesaría cero y la mesa
     * que sigue ocupada aparecería libre.
     *
     * <p>Deliberadamente NO se deriva de {@code MAX_HOURS_PER_DESK_PER_DAY}: esa
     * constante ya carga con tres significados (cupo de mesa, tamaño de trozo y tamaño
     * de tarea) y no conviene colgarle un cuarto.
     */
    public static final double MIN_IN_PROGRESS_DESK_HOURS = 1.0;

    private final TaskItemRepository taskItemRepository;

    /**
     * Horas base de cupo. Es exactamente lo que hacían las tres copias privadas.
     *
     * <p>Una tarea de venta en línea pesa 0.0: no es que tenga permiso para pasarse del
     * cupo, es que no ocupa nada.
     */
    public double baseHours(TaskEntity task) {
        if (task == null) return 0.0;
        if (ProductionPlanningConstants.isOnlineSaleOrder(null, task.getProductionOrderCode())) {
            return 0.0;
        }
        double extra = 0.0;
        if (task.getId() != null) {
            extra = sumaExtra(taskItemRepository.findByTaskId(task.getId()));
        }
        return ProductionPlanningConstants.deskCupoBaseHours(
                task.getEstimatedHours(), task.getProductionOrderCode(), extra);
    }

    /**
     * Horas de venta del día de muchas tareas, de una sola consulta.
     *
     * <p>Quien reparta mesas sobre una lista grande debe cargar este mapa una vez y usar
     * las sobrecargas que lo reciben. La versión de una tarea hace una consulta por
     * llamada, y los repartidores la invocan dentro del comparador y de dos bucles: sobre
     * mil tareas eso son miles de viajes a la base dentro de la transacción que sostiene
     * el candado, y la corrida pasa de segundos a minutos.
     */
    public Map<Long, Double> daySaleExtraByTaskId(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        return taskItemRepository.findByTaskIdIn(new ArrayList<>(new LinkedHashSet<>(taskIds))).stream()
                .filter(item -> Boolean.TRUE.equals(item.getDaySaleExtra()))
                .filter(item -> item.getTaskId() != null && item.getEstimatedHours() != null)
                .collect(Collectors.groupingBy(TaskItemEntity::getTaskId,
                        Collectors.summingDouble(TaskItemEntity::getEstimatedHours)));
    }

    /** Igual que {@link #baseHours(TaskEntity)} pero sin consultar: usa el mapa ya cargado. */
    public double baseHours(TaskEntity task, Map<Long, Double> extraByTaskId) {
        if (task == null) return 0.0;
        if (ProductionPlanningConstants.isOnlineSaleOrder(null, task.getProductionOrderCode())) {
            return 0.0;
        }
        double extra = task.getId() == null ? 0.0
                : extraByTaskId.getOrDefault(task.getId(), 0.0);
        return ProductionPlanningConstants.deskCupoBaseHours(
                task.getEstimatedHours(), task.getProductionOrderCode(), extra);
    }

    private double sumaExtra(List<TaskItemEntity> items) {
        return items.stream()
                .filter(item -> Boolean.TRUE.equals(item.getDaySaleExtra()))
                .map(TaskItemEntity::getEstimatedHours)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Horas que una tarea le sigue quitando a la mesa, descontando lo que ya lleva
     * abierta. Para todo lo que no esté IN_PROGRESS devuelve las horas base.
     *
     * <p><b>Es una aproximación, y por arriba.</b> No existe ningún campo con el avance
     * real: {@code actualDurationMinutes} solo se escribe al completar la tarea, y
     * {@code task_item} no guarda cantidad producida. Lo único medible es el tiempo de
     * jornada transcurrido desde {@code startedAt}, y ese tiempo incluye las horas en
     * que la tarea estuvo abierta sin que nadie la tocara. O sea: lo descontado es una
     * cota superior de lo trabajado, nunca el dato real.
     *
     * @param now momento de referencia; se captura una sola vez por petición para que
     *            una misma corrida sea consistente consigo misma.
     */
    public double remainingDeskHours(TaskEntity task, LocalDateTime now, Map<Long, Double> extraByTaskId) {
        return remaining(task, now, baseHours(task, extraByTaskId));
    }

    public double remainingDeskHours(TaskEntity task, LocalDateTime now) {
        return remaining(task, now, baseHours(task));
    }

    private double remaining(TaskEntity task, LocalDateTime now, double base) {

        // Las de venta en línea valen 0 h de cupo: aplicarles el piso las haría pesar
        // más estando a medias que recién creadas.
        if (base <= 0.0) return 0.0;

        if (task == null || !"IN_PROGRESS".equals(task.getStatus()) || task.getStartedAt() == null) {
            return base;
        }
        if (now == null) return base;

        double transcurrido = ProductionShift.workingMinutesBetween(task.getStartedAt(), now) / 60.0;
        double restante = base - transcurrido;

        if (restante < MIN_IN_PROGRESS_DESK_HOURS) return MIN_IN_PROGRESS_DESK_HOURS;
        if (restante > base) return base;
        return restante;
    }
}
