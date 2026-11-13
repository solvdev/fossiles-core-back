package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.request.MaquilaDispatchRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaquilaDispatchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SpecialDeskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * «Enviar a maquila» de Armar el día. Lo elegido se manda directo a la mesa de maquila del día,
 * una tarea por OP:
 * <ul>
 *   <li>sin tope de horas (ni las 4 h ideales ni las 5 h duras) y sin límite de productos;</li>
 *   <li>no pasa por la propuesta de Armar el día ni consume cupo de la planta;</li>
 *   <li>nace ya con la mesa de maquila: el reparto automático y el corte no la mueven de ahí
 *       ({@code TaskDeskAssignerService} solo coloca tareas sin mesa).</li>
 * </ul>
 * El restante de cada producto se valida igual que en cualquier tarea (con bloqueo de los ítems),
 * y si algo no alcanza no se crea ninguna.
 *
 * <p>Por defecto el cuero sale con el envío: se entrega producto por producto y, con el interruptor
 * de cuero por producto encendido, se descuenta su receta del kardex. Si falta cuero o receta para
 * algún producto, no se crea nada.
 */
@Service
@RequiredArgsConstructor
public class MaquilaDispatchService {

    private static final String DEFAULT_OBSERVATIONS = "Maquila";

    private final TaskOrganizerService taskOrganizerService;
    private final ProductionDeskCountService productionDeskCountService;
    private final ProductionPlanningLock productionPlanningLock;
    private final TaskLeatherDeliveryService taskLeatherDeliveryService;
    private final LeatherMovementRepository leatherMovementRepository;
    private final TaskRepository taskRepository;

    @Transactional(rollbackFor = Exception.class)
    public MaquilaDispatchResponse send(MaquilaDispatchRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getDate() == null) {
            throw new BusinessException("Debe indicar el día de trabajo.");
        }
        if (!ProductionPlanningConstants.isWorkday(request.getDate())) {
            throw new BusinessException("Solo se puede programar de lunes a viernes: "
                    + request.getDate() + " es fin de semana.");
        }
        SpecialDeskResponse maquila = resolveDesk(request.getDesk());

        List<MaquilaDispatchRequest.Order> orders = request.getOrders() == null ? List.of()
                : request.getOrders().stream().filter(Objects::nonNull).toList();
        Set<Long> seenOrders = new HashSet<>();
        for (MaquilaDispatchRequest.Order o : orders) {
            if (o.getProductionOrderId() == null) {
                throw new BusinessException("Hay una orden sin id.");
            }
            if (!seenOrders.add(o.getProductionOrderId())) {
                throw new BusinessException("La orden " + o.getProductionOrderId()
                        + " viene repetida. Junte sus productos en una sola.");
            }
        }

        // Mismo turno que el reparto de Armar el día: nadie toma unidades mientras se valida.
        productionPlanningLock.acquire();

        String observations = request.getObservations() == null || request.getObservations().isBlank()
                ? DEFAULT_OBSERVATIONS : request.getObservations().trim();
        Map<Long, Integer> reserved = new HashMap<>();
        List<MaquilaDispatchResponse.Created> created = new ArrayList<>();
        int totalQty = 0;
        double totalHours = 0.0;
        double leatherFt2 = 0.0;
        boolean deliverLeather = request.getDeliverLeather() == null || request.getDeliverLeather();

        for (MaquilaDispatchRequest.Order o : orders) {
            List<CreateManualTaskRequest.ManualTaskItemRequest> items = o.getItems() == null ? List.of()
                    : o.getItems().stream()
                    .filter(Objects::nonNull)
                    .filter(i -> i.getQuantity() != null && i.getQuantity() > 0)
                    .toList();
            if (items.isEmpty()) {
                continue;
            }
            CreateManualTaskRequest taskRequest = CreateManualTaskRequest.builder()
                    .productionOrderId(o.getProductionOrderId())
                    .items(items)
                    .scheduledDate(request.getDate())
                    .desk(maquila.getDesk())
                    .observations(observations)
                    .build();
            // AUTO_CENTRO: mismas reglas que una tarea de centro, pero sin el tope de 5 h.
            TaskOrganizerService.TaskDraft draft = taskOrganizerService.prepareDraft(
                    taskRequest, TaskOrganizerService.CreateMode.AUTO_CENTRO, true, reserved);
            draft.quantityByItemId().forEach((id, q) -> reserved.merge(id, q, Integer::sum));
            TaskEntity task = taskOrganizerService.persistDraft(draft, request.getDate(), maquila.getDesk());
            double taskFt2 = deliverLeather ? deliverLeather(task, draft.items()) : 0.0;
            leatherFt2 += taskFt2;

            totalQty += draft.totalQuantity();
            totalHours += draft.totalHours();
            created.add(MaquilaDispatchResponse.Created.builder()
                    .taskId(task.getId())
                    .code(task.getCode())
                    .productionOrderCode(task.getProductionOrderCode())
                    .quantity(draft.totalQuantity())
                    .hours(Math.round(draft.totalHours() * 100.0) / 100.0)
                    .leatherFt2(round2(taskFt2))
                    .build());
        }
        if (created.isEmpty()) {
            throw new BusinessException("Elija al menos un producto con cantidad para mandar a maquila.");
        }

        return MaquilaDispatchResponse.builder()
                .date(request.getDate())
                .desk(maquila.getDesk())
                .deskName(maquila.getName())
                .totalQuantity(totalQty)
                .totalHours(Math.round(totalHours * 100.0) / 100.0)
                .leatherDelivered(deliverLeather)
                .leatherFt2(round2(leatherFt2))
                .created(created)
                .build();
    }

    /**
     * El cuero sale al maquilador al enviar: cada producto pasa por la misma entrega que el clic
     * «Entregar cuero» ({@link TaskLeatherDeliveryService#deliver}). Con el interruptor de cuero por
     * producto encendido descuenta la receta del kardex; si a un producto le falta cuero o receta, lanza
     * y no se crea nada del envío. Devuelve los ft² que salieron.
     */
    private double deliverLeather(TaskEntity task, List<TaskItemEntity> items) throws BusinessException {
        double ft2 = 0.0;
        for (TaskItemEntity item : items) {
            taskLeatherDeliveryService.deliver(task, item);
            ft2 += leatherMovementRepository.findFirstByTaskItemIdAndMovementType(item.getId(), "SALIDA")
                    .map(m -> m.getQuantity() == null ? 0.0 : m.getQuantity().doubleValue())
                    .orElse(0.0);
        }
        boolean all = items.stream().allMatch(i -> Boolean.TRUE.equals(i.getLeatherDelivered()));
        if (all) {
            task.setLeatherDelivered(true);
            task.setLeatherDeliveredAt(GuatemalaDateTime.now());
            taskRepository.save(task);
        }
        return ft2;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private SpecialDeskResponse resolveDesk(Integer desk) throws BusinessException {
        List<SpecialDeskResponse> active = productionDeskCountService.activeSpecialDesks();
        if (active.isEmpty()) {
            throw new BusinessException("No hay mesa de maquila activa. Actívela en el Tablero → Encargados por mesa.");
        }
        if (desk == null) {
            if (active.size() > 1) {
                throw new BusinessException("Hay más de una mesa de maquila: indique a cuál se manda.");
            }
            return active.get(0);
        }
        return active.stream().filter(s -> Objects.equals(s.getDesk(), desk)).findFirst()
                .orElseThrow(() -> new BusinessException("La mesa " + desk + " no es una mesa de maquila activa."));
    }
}
