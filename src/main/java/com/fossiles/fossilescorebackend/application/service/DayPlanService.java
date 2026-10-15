package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.request.DayPlanRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanConfirmResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse.Status;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.DayPlanConflictException;
import com.fossiles.fossilescorebackend.application.exception.QuantityUnavailableException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.DeskSlotFinder;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderPlanPriority;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «Armar el día» del Centro de Producción: valida y crea de una sola vez las tareas del día.
 *
 * <p><b>Vista previa</b> ({@link #preview}) mide cada tarea borrador con las mismas reglas de
 * {@link TaskOrganizerService#prepareDraft} y propone día y mesa. No guarda nada.
 *
 * <p><b>Confirmar</b> ({@link #confirm}) repite el cálculo bajo el candado de planificación y
 * crea todas las tareas o ninguna. Las tareas manuales nacen sin corte, así que se guarda
 * solo la fecha y la mesa queda NULL: la real la pone {@code TaskDeskAssignerService.place}
 * al marcar el corte. La mesa de la vista previa es solo orientativa.
 *
 * <p>La huella ({@code planFingerprint}) resume lo que la propuesta da por cierto: carga por
 * día y mesa, restante de los ítems y prioridades. Si cambia entre la vista previa y confirmar
 * se responde 409 PLAN_CHANGED; además impide que un doble clic duplique tareas.
 */
@Service
@RequiredArgsConstructor
public class DayPlanService {

    public static final int DEFAULT_HORIZON_DAYS = 5;
    public static final int MAX_HORIZON_DAYS = 10;
    private static final double EPS = 1e-9;
    private static final String AUTO_PLAN_PREFIX = "Auto-plan";

    private final TaskOrganizerService taskOrganizerService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductRepository productRepository;
    private final ProductionDeskCountService productionDeskCountService;
    private final TaskDeskHoursService taskDeskHoursService;
    private final LeatherRequirementService leatherRequirementService;
    private final ProductionPlanningLock productionPlanningLock;
    private final EntityManager entityManager;

    // ==================== API ====================

    @Transactional(readOnly = true)
    public DayPlanPreviewResponse preview(DayPlanRequest request) throws BusinessException {
        return buildPlan(request, false, GuatemalaDateTime.now()).toResponse();
    }

    /**
     * Crea todas las tareas de la propuesta o ninguna.
     *
     * <p>{@code rollbackFor = Exception.class}: {@link BusinessException} es checked y por
     * defecto no revierte. Los fallos de la propuesta salen como
     * {@link DayPlanConflictException} (unchecked) con el detalle por tarea.
     */
    @Transactional(rollbackFor = Exception.class)
    public DayPlanConfirmResponse confirm(DayPlanRequest request) throws BusinessException {
        if (request == null || request.getPlanFingerprint() == null || request.getPlanFingerprint().isBlank()) {
            throw new BusinessException("Falta la huella de la vista previa (planFingerprint).");
        }
        validateShape(request);

        lockForPlanning(request);

        Plan plan = buildPlan(request, true, GuatemalaDateTime.now());

        List<DayPlanConflictException.Failure> qty = plan.failures(true);
        if (!qty.isEmpty()) {
            throw new DayPlanConflictException(DayPlanConflictException.QTY_UNAVAILABLE,
                    "Alguien más ya tomó unidades de algunos productos. No se creó ninguna tarea.",
                    qty, plan.toResponse());
        }
        List<DayPlanConflictException.Failure> blocked = plan.failures(false);
        if (!blocked.isEmpty()) {
            throw new DayPlanConflictException(DayPlanConflictException.PLAN_BLOCKED,
                    "Hay tareas que no se pueden crear. No se creó ninguna.", blocked, plan.toResponse());
        }
        boolean accept = Boolean.TRUE.equals(request.getAcceptChanges());
        if (!accept && !request.getPlanFingerprint().equals(plan.fingerprint)) {
            throw new DayPlanConflictException(DayPlanConflictException.PLAN_CHANGED,
                    "La carga de las mesas cambió desde la vista previa. Revise la propuesta nueva.",
                    List.of(), plan.toResponse());
        }

        savePriorities(plan);

        Map<String, DayPlanConfirmResponse.Created> createdByClient = new HashMap<>();
        for (Planned p : plan.byPlanOrder) {
            TaskEntity task;
            try {
                // Mesa NULL: la tarea nace sin corte y la mesa real llega al marcarlo.
                task = taskOrganizerService.persistDraft(p.prepared, p.proposedDate, null);
            } catch (RuntimeException e) {
                if (isLockTimeout(e)) {
                    throw lockTimeout();
                }
                throw e;
            }
            createdByClient.put(p.clientId, DayPlanConfirmResponse.Created.builder()
                    .clientId(p.clientId)
                    .taskId(task.getId())
                    .code(task.getCode())
                    .scheduledDate(task.getScheduledDate())
                    .indicativeDesk(p.indicativeDesk)
                    .build());
        }

        List<DayPlanConfirmResponse.Created> created = new ArrayList<>();
        List<DayPlanPreviewResponse.NotPlaced> notPlaced = new ArrayList<>();
        for (Planned p : plan.byRequestOrder) {
            created.add(createdByClient.get(p.clientId));
            if (p.notPlaced) {
                notPlaced.add(p.toNotPlaced());
            }
        }
        return DayPlanConfirmResponse.builder().created(created).notPlaced(notPlaced).build();
    }

    // ==================== BLOQUEOS ====================

    /**
     * Orden fijo, el mismo que {@code createManualTask}: candado de planificación y luego los
     * ítems ordenados por id. Con otro orden, dos reparto simultáneos se podrían interbloquear.
     */
    private void lockForPlanning(DayPlanRequest request) {
        try {
            entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
            productionPlanningLock.acquire();
            List<Long> itemIds = requestedItemIds(request);
            if (!itemIds.isEmpty()) {
                productionOrderItemRepository.findAllByIdForUpdate(itemIds);
            }
            entityManager.createNativeQuery("SET LOCAL lock_timeout = 0").executeUpdate();
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                throw lockTimeout();
            }
            throw e;
        }
    }

    private static DayPlanConflictException lockTimeout() {
        return new DayPlanConflictException(DayPlanConflictException.LOCK_TIMEOUT,
                "El sistema está ocupado con otro reparto. No se creó ninguna tarea; intente otra vez.",
                List.of(), null);
    }

    static boolean isLockTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static List<Long> requestedItemIds(DayPlanRequest request) {
        return request.getTasks().stream()
                .filter(Objects::nonNull)
                .filter(d -> d.getItems() != null)
                .flatMap(d -> d.getItems().stream())
                .filter(Objects::nonNull)
                .map(CreateManualTaskRequest.ManualTaskItemRequest::getProductionOrderItemId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    // ==================== CÁLCULO ====================

    private static void validateShape(DayPlanRequest request) throws BusinessException {
        if (request == null || request.getAnchorDate() == null) {
            throw new BusinessException("Debe indicar el día de partida (anchorDate).");
        }
        if (request.getTasks() == null || request.getTasks().isEmpty()) {
            throw new BusinessException("Debe indicar al menos una tarea.");
        }
        Set<String> seen = new HashSet<>();
        for (DayPlanRequest.Draft draft : request.getTasks()) {
            if (draft == null || draft.getClientId() == null || draft.getClientId().isBlank()) {
                throw new BusinessException("Cada tarea necesita un clientId.");
            }
            if (!seen.add(draft.getClientId())) {
                throw new BusinessException("El clientId " + draft.getClientId() + " está repetido.");
            }
        }
    }

    /** Una tarea del borrador, con lo que se fue averiguando de ella. */
    private static final class Planned {
        int index;
        String clientId;
        DayPlanRequest.Draft draft;
        ProductionOrderEntity order;
        TaskOrganizerService.TaskDraft prepared;
        Status status = Status.OK;
        final List<String> messages = new ArrayList<>();
        boolean quantityFailure;
        boolean overcapAnchor;
        boolean notPlaced;
        LocalDate proposedDate;
        LocalDate nextDayCandidate;
        Integer indicativeDesk;

        double baseHours() {
            return prepared == null ? 0.0 : round2(prepared.baseHours());
        }

        double totalHours() {
            return prepared == null ? 0.0 : round2(prepared.totalHours());
        }

        void block(String message) {
            status = Status.BLOCKED;
            messages.add(message);
        }

        void warn(String message) {
            if (status != Status.BLOCKED) {
                status = Status.WARN;
            }
            messages.add(message);
        }

        DayPlanPreviewResponse.TaskPreview toPreview() {
            return DayPlanPreviewResponse.TaskPreview.builder()
                    .clientId(clientId)
                    .status(status)
                    .messages(List.copyOf(messages))
                    .baseHours(baseHours())
                    .totalHours(totalHours())
                    .overcapAnchor(overcapAnchor)
                    .proposedDate(proposedDate)
                    .nextDayCandidate(nextDayCandidate)
                    .indicativeDesk(indicativeDesk)
                    .build();
        }

        DayPlanPreviewResponse.NotPlaced toNotPlaced() {
            return DayPlanPreviewResponse.NotPlaced.builder()
                    .clientId(clientId)
                    .reason(messages.isEmpty() ? "Sin mesa libre en el horizonte." : messages.get(messages.size() - 1))
                    .nextDayCandidate(nextDayCandidate)
                    .build();
        }
    }

    private static final class Plan {
        LocalDateTime asOf;
        String fingerprint;
        DayPlanPreviewResponse.Limits limits;
        List<Planned> byRequestOrder = new ArrayList<>();
        List<Planned> byPlanOrder = new ArrayList<>();
        List<DayPlanPreviewResponse.DayLoad> dayLoads = new ArrayList<>();
        Map<Long, Integer> priorityOverrides = new HashMap<>();
        Map<Long, ProductionOrderEntity> orders = new HashMap<>();

        boolean blocked() {
            return byRequestOrder.stream().anyMatch(p -> p.status == Status.BLOCKED);
        }

        List<DayPlanConflictException.Failure> failures(boolean quantityOnly) {
            return byRequestOrder.stream()
                    .filter(p -> p.status == Status.BLOCKED)
                    .filter(p -> !quantityOnly || p.quantityFailure)
                    .map(p -> new DayPlanConflictException.Failure(p.clientId, String.join(" ", p.messages)))
                    .toList();
        }

        DayPlanPreviewResponse toResponse() {
            return DayPlanPreviewResponse.builder()
                    .asOf(asOf)
                    .planFingerprint(fingerprint)
                    .limits(limits)
                    .tasks(byRequestOrder.stream().map(Planned::toPreview).toList())
                    .dayLoads(dayLoads)
                    .notPlaced(byRequestOrder.stream().filter(p -> p.notPlaced).map(Planned::toNotPlaced).toList())
                    .blocked(blocked())
                    .build();
        }
    }

    /** Tareas de un día que ocupan mesa, ya filtradas, con lo necesario para medirlas. */
    private record DayData(LocalDate date, int numDesks, List<TaskEntity> tasks,
                           Map<Long, Double> extraByTaskId, Set<Long> cinchoOnlyTaskIds) {}

    private Plan buildPlan(DayPlanRequest request, boolean lockItems, LocalDateTime asOf) throws BusinessException {
        validateShape(request);
        Plan plan = new Plan();
        plan.asOf = asOf;

        int horizon = Math.min(Math.max(request.getHorizonDays() == null ? DEFAULT_HORIZON_DAYS
                : request.getHorizonDays(), 1), MAX_HORIZON_DAYS);
        LocalDate firstDay = DeskSlotFinder.nextWorkday(request.getAnchorDate());
        List<LocalDate> horizonDays = new ArrayList<>();
        for (LocalDate d = firstDay; horizonDays.size() < horizon; d = DeskSlotFinder.nextWorkday(d.plusDays(1))) {
            horizonDays.add(d);
        }
        LocalDate afterHorizon = DeskSlotFinder.nextWorkday(horizonDays.get(horizonDays.size() - 1).plusDays(1));

        // ---- OPs y prioridades (sin tocar las entidades: la vista previa no guarda nada)
        Set<Long> orderIds = request.getTasks().stream().map(DayPlanRequest.Draft::getProductionOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (ProductionOrderEntity po : productionOrderRepository.findAllById(orderIds)) {
            plan.orders.put(po.getId(), po);
        }
        plan.priorityOverrides = parsePriorities(request.getOrderPriorities(), plan.orders);

        int index = 0;
        for (DayPlanRequest.Draft draft : request.getTasks()) {
            Planned p = new Planned();
            p.index = index++;
            p.clientId = draft.getClientId();
            p.draft = draft;
            p.order = draft.getProductionOrderId() == null ? null : plan.orders.get(draft.getProductionOrderId());
            plan.byRequestOrder.add(p);
        }

        // ---- Orden de reparto: OPL, OPCK, prioridad manual, FIFO; dentro de una OP, el del pedido
        Comparator<ProductionOrderEntity> byOrder = ProductionOrderPlanPriority.comparator(plan.priorityOverrides);
        plan.byPlanOrder = new ArrayList<>(plan.byRequestOrder);
        plan.byPlanOrder.sort((a, b) -> {
            if (a.order == null || b.order == null) {
                if (a.order == b.order) return Integer.compare(a.index, b.index);
                return a.order == null ? 1 : -1;
            }
            int c = byOrder.compare(a.order, b.order);
            return c != 0 ? c : Integer.compare(a.index, b.index);
        });

        // ---- Validación con el restante acumulado entre borradores
        validateDrafts(plan, lockItems);

        // ---- Cargas actuales por día (vista y huella)
        TreeSet<LocalDate> days = new TreeSet<>(horizonDays);
        for (Planned p : plan.byPlanOrder) {
            if (p.status != Status.BLOCKED && p.draft.getScheduledDate() != null) {
                days.add(p.draft.getScheduledDate());
            }
        }
        Map<LocalDate, DayData> data = new HashMap<>();
        Map<LocalDate, Map<Integer, Double>> shown = new HashMap<>();
        Map<LocalDate, Map<Integer, Double>> stable = new HashMap<>();
        for (LocalDate day : days) {
            DayData dd = loadDay(day, firstDay);
            data.put(day, dd);
            shown.put(day, loads(dd, asOf));
            stable.put(day, loads(dd, null));
        }
        plan.limits = DayPlanPreviewResponse.Limits.builder()
                .idealHours(ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY)
                .maxHours(ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP)
                .numDesks(data.get(firstDay).numDesks())
                .build();
        plan.fingerprint = fingerprint(days, data, stable, plan);

        // ---- Día y mesa orientativa, en el orden de reparto
        Map<LocalDate, Map<Integer, Double>> before = new HashMap<>();
        shown.forEach((d, m) -> before.put(d, new HashMap<>(m)));
        Map<LocalDate, Map<Integer, Double>> added = new HashMap<>();
        for (Planned p : plan.byPlanOrder) {
            if (p.status != Status.BLOCKED) {
                placeTask(p, firstDay, horizonDays, afterHorizon, data, shown, added);
            }
        }
        for (LocalDate day : days) {
            int desks = data.get(day).numDesks();
            for (int desk = 1; desk <= desks; desk++) {
                plan.dayLoads.add(DayPlanPreviewResponse.DayLoad.builder()
                        .date(day).desk(desk)
                        .currentHours(round2(before.get(day).getOrDefault(desk, 0.0)))
                        .addedHours(round2(added.getOrDefault(day, Map.of()).getOrDefault(desk, 0.0)))
                        .build());
            }
        }
        return plan;
    }

    private static Map<Long, Integer> parsePriorities(Map<String, Integer> raw, Map<Long, ProductionOrderEntity> orders) {
        Map<Long, Integer> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null
                    || !ProductionPlanningConstants.isManualSchedulingPriority(e.getValue())) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(e.getKey().trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            ProductionOrderEntity po = orders.get(id);
            // Las OPL tienen su cupo reservado (0): una prioridad manual no las mueve.
            if (po != null && !ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode())) {
                out.put(id, e.getValue());
            }
        }
        return out;
    }

    /** Valida cada borrador con las reglas de la tarea manual, restando lo que piden los anteriores. */
    private void validateDrafts(Plan plan, boolean lockItems) {
        Map<Long, Integer> reserved = new HashMap<>();
        Map<Long, BigDecimal> reservedLeather = new HashMap<>();
        for (Planned p : plan.byPlanOrder) {
            DayPlanRequest.Draft draft = p.draft;
            if (draft.getObservations() != null && draft.getObservations().trim().startsWith(AUTO_PLAN_PREFIX)) {
                // «regenerate» del autoplan borra las tareas con esta observación.
                p.block("Las observaciones no pueden empezar por «" + AUTO_PLAN_PREFIX + "».");
                continue;
            }
            CreateManualTaskRequest manual = CreateManualTaskRequest.builder()
                    .productionOrderId(draft.getProductionOrderId())
                    .items(draft.getItems() == null ? null : draft.getItems().stream()
                            .filter(Objects::nonNull)
                            .map(i -> CreateManualTaskRequest.ManualTaskItemRequest.builder()
                                    .productionOrderItemId(i.getProductionOrderItemId())
                                    .quantity(i.getQuantity())
                                    .daySaleExtra(i.getDaySaleExtra())
                                    .build())
                            .toList())
                    .desk(null)
                    .scheduledDate(draft.getScheduledDate())
                    .observations(draft.getObservations())
                    .build();
            try {
                p.prepared = taskOrganizerService.prepareDraft(
                        manual, TaskOrganizerService.CreateMode.MANUAL, lockItems, reserved);
            } catch (QuantityUnavailableException e) {
                p.quantityFailure = true;
                p.block(e.getMessage());
                continue;
            } catch (BusinessException | ResourceNotFoundException e) {
                p.block(e.getMessage());
                continue;
            }
            p.prepared.quantityByItemId().forEach((id, qty) -> reserved.merge(id, qty, Integer::sum));

            double base = p.baseHours();
            if (base > ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY + EPS) {
                p.warn(String.format(Locale.ROOT, "La tarea pasa de %.0f h (%.2f h); el tope es %.0f h.",
                        ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY, base,
                        ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP));
            }
            warnLeather(p, reservedLeather);
        }
    }

    /** Falta de cuero: solo aviso, igual que el autoplan decide si una línea se puede cubrir. */
    private void warnLeather(Planned p, Map<Long, BigDecimal> reservedLeather) {
        Set<Long> productIds = p.prepared.items().stream().map(TaskItemEntity::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return;
        }
        Map<Long, ProductEntity> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, x -> x, (a, b) -> a));
        for (TaskItemEntity line : p.prepared.items()) {
            ProductEntity product = line.getProductId() == null ? null : products.get(line.getProductId());
            if (product == null || line.getQuantity() == null) {
                continue;
            }
            LeatherRequirementService.LeatherNeed need =
                    leatherRequirementService.resolveNeed(product, line.getColorId(), line.getQuantity());
            if (need.blocked() || !leatherRequirementService.canCover(need, reservedLeather)) {
                p.warn(product.getCode() + ": " + leatherRequirementService.shortageMessage(need, reservedLeather));
            } else if (!need.noneRequired()) {
                reservedLeather.merge(need.materialId(), need.qtyFt2(), BigDecimal::add);
            }
        }
    }

    // ==================== CARGAS ====================

    private DayData loadDay(LocalDate day, LocalDate firstDay) throws BusinessException {
        List<TaskEntity> raw = new ArrayList<>(taskRepository.findByScheduledDate(day));
        if (day.equals(firstDay)) {
            // Las IN_PROGRESS de días anteriores siguen ocupando mesa: se pliegan al primer día hábil.
            raw.addAll(taskRepository.findUnfinishedCarryOver(firstDay));
        }
        Map<Long, TaskEntity> unique = new LinkedHashMap<>();
        for (TaskEntity t : raw) {
            if (("PENDING".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus())) && t.getId() != null) {
                unique.putIfAbsent(t.getId(), t);
            }
        }
        List<TaskEntity> tasks = new ArrayList<>(unique.values());
        tasks.sort(Comparator.comparing(TaskEntity::getId));
        Map<Long, Double> extra = taskDeskHoursService.daySaleExtraByTaskId(tasks.stream().map(TaskEntity::getId).toList());
        Set<Long> cincho = cinchoOnlyTaskIds(tasks.stream().filter(t -> t.getDesk() == null).map(TaskEntity::getId).toList());
        return new DayData(day, productionDeskCountService.getDay(day).getNumDesks(), tasks, extra, cincho);
    }

    /**
     * Carga por mesa de un día. Las tareas sin mesa (esperan el corte) se acomodan en una mesa
     * virtual, la menos cargada, igual que {@code ProductionAutoPlannerService.loadDayLoads},
     * para que el cupo del día sea el mismo antes y después de cortar.
     *
     * @param asOf {@code null} = horas base sin reloj (huella); con valor, las IN_PROGRESS
     *             descuentan lo ya trabajado
     */
    private Map<Integer, Double> loads(DayData dd, LocalDateTime asOf) {
        Map<Integer, Double> loads = new HashMap<>();
        List<Double> withoutDesk = new ArrayList<>();
        for (TaskEntity t : dd.tasks()) {
            double hours = asOf == null
                    ? taskDeskHoursService.baseHours(t, dd.extraByTaskId())
                    : taskDeskHoursService.remainingDeskHours(t, asOf, dd.extraByTaskId());
            if (t.getDesk() != null) {
                loads.merge(t.getDesk(), hours, Double::sum);
            } else if (hours > 0 && !dd.cinchoOnlyTaskIds().contains(t.getId())) {
                withoutDesk.add(hours);
            }
        }
        withoutDesk.sort(Comparator.reverseOrder());
        for (double hours : withoutDesk) {
            loads.merge(lightestDesk(loads, dd.numDesks()), hours, Double::sum);
        }
        return loads;
    }

    private static int lightestDesk(Map<Integer, Double> loads, int numDesks) {
        int best = 1;
        for (int desk = 2; desk <= Math.max(numDesks, 1); desk++) {
            if (loads.getOrDefault(desk, 0.0) < loads.getOrDefault(best, 0.0) - EPS) {
                best = desk;
            }
        }
        return best;
    }

    /** Tareas cuyos productos son todos cincho: se trabajan aparte del centro. */
    private Set<Long> cinchoOnlyTaskIds(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Set.of();
        }
        List<TaskItemEntity> items = taskItemRepository.findByTaskIdIn(taskIds);
        if (items == null || items.isEmpty()) {
            return Set.of();
        }
        Set<Long> productIds = items.stream().map(TaskItemEntity::getProductId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        Map<Long, List<TaskItemEntity>> byTask = items.stream().filter(i -> i.getTaskId() != null)
                .collect(Collectors.groupingBy(TaskItemEntity::getTaskId));
        Set<Long> out = new HashSet<>();
        byTask.forEach((taskId, list) -> {
            if (list.stream().allMatch(i -> i.getProductId() != null
                    && CinchoProductUtils.isCinchoLineForProduction(products.get(i.getProductId())))) {
                out.add(taskId);
            }
        });
        return out;
    }

    // ==================== DÍA Y MESA ORIENTATIVA ====================

    private void placeTask(Planned p, LocalDate firstDay, List<LocalDate> horizonDays, LocalDate afterHorizon,
                           Map<LocalDate, DayData> data, Map<LocalDate, Map<Integer, Double>> shown,
                           Map<LocalDate, Map<Integer, Double>> added) {
        double hours = p.baseHours();
        boolean canOvercap = p.order != null && ProductionPlanningConstants.canOvercapDeskDay(p.order.getOrderType());
        LocalDate fixed = p.draft.getScheduledDate();

        if (fixed != null || canOvercap) {
            // Día elegido por el jefe, u OPL/OPCK, que van el primer día aunque la mesa se pase.
            LocalDate day = fixed != null ? fixed : firstDay;
            Map<Integer, Double> dayLoads = shown.get(day);
            int numDesks = data.get(day).numDesks();
            Integer desk = DeskSlotFinder.pickDeskOnDayLowest(dayLoads, numDesks, hours);
            if (desk == null) {
                desk = lightestDesk(dayLoads, numDesks);
                double after = dayLoads.getOrDefault(desk, 0.0) + hours;
                if (hours > EPS && after > ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY + EPS) {
                    if (canOvercap) {
                        p.overcapAnchor = true;
                        p.warn(String.format(Locale.ROOT,
                                "Pasa del cupo de la mesa %d (%.2f h) el %s; se permite para OPL/OPCK.", desk, after, day));
                    } else {
                        p.warn(String.format(Locale.ROOT,
                                "La mesa %d quedaría con %.2f h el %s (más de %.0f h).", desk, after, day,
                                ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY));
                        p.nextDayCandidate = DeskSlotFinder.nextWorkday(day.plusDays(1));
                    }
                }
            }
            commit(p, day, desk, hours, shown, added);
            return;
        }

        for (LocalDate day : horizonDays) {
            Integer desk = DeskSlotFinder.pickDeskOnDayLowest(shown.get(day), data.get(day).numDesks(), hours);
            if (desk != null) {
                commit(p, day, desk, hours, shown, added);
                return;
            }
        }
        // No cabe en ningún día del horizonte: se crea igual, sin mesa, en el día siguiente al horizonte.
        p.notPlaced = true;
        p.proposedDate = afterHorizon;
        p.nextDayCandidate = afterHorizon;
        p.indicativeDesk = null;
        p.warn(String.format(Locale.ROOT, "Ninguna mesa tiene lugar para %.2f h en %d días hábiles; "
                + "queda para el %s.", hours, horizonDays.size(), afterHorizon));
    }

    private static void commit(Planned p, LocalDate day, int desk, double hours,
                               Map<LocalDate, Map<Integer, Double>> shown,
                               Map<LocalDate, Map<Integer, Double>> added) {
        p.proposedDate = day;
        p.indicativeDesk = desk;
        if (hours > 0) {
            shown.get(day).merge(desk, hours, Double::sum);
            added.computeIfAbsent(day, d -> new HashMap<>()).merge(desk, hours, Double::sum);
        }
    }

    // ==================== HUELLA ====================

    private String fingerprint(Collection<LocalDate> days, Map<LocalDate, DayData> data,
                               Map<LocalDate, Map<Integer, Double>> stable, Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (LocalDate day : new TreeSet<>(days)) {
            sb.append("D|").append(day).append('|').append(data.get(day).numDesks());
            new TreeMap<>(stable.get(day)).forEach((desk, h) -> {
                if (Math.abs(h) > EPS) {
                    sb.append('|').append(desk).append('=').append(String.format(Locale.ROOT, "%.2f", h));
                }
            });
            sb.append('\n');
        }
        Set<Long> itemIds = plan.byRequestOrder.stream().flatMap(p -> p.draft.getItems() == null
                        ? java.util.stream.Stream.<CreateManualTaskRequest.ManualTaskItemRequest>empty()
                        : p.draft.getItems().stream())
                .filter(Objects::nonNull).map(CreateManualTaskRequest.ManualTaskItemRequest::getProductionOrderItemId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        if (!itemIds.isEmpty()) {
            Map<Long, Integer> assigned = taskItemRepository.assignedQuantityMap(itemIds);
            for (ProductionOrderItemEntity item : productionOrderItemRepository.findAllById(itemIds).stream()
                    .sorted(Comparator.comparing(ProductionOrderItemEntity::getId)).toList()) {
                int remaining = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item)
                        - (assigned == null ? 0 : assigned.getOrDefault(item.getId(), 0));
                sb.append("I|").append(item.getId()).append('|').append(remaining).append('\n');
            }
        }
        new TreeMap<>(plan.orders).forEach((id, po) -> sb.append("P|").append(id).append('|')
                .append(ProductionOrderPlanPriority.effective(po, plan.priorityOverrides)).append('\n'));
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== PRIORIDADES ====================

    /** Las prioridades de la propuesta se guardan bajo el candado, no con el PUT global de la cola. */
    private void savePriorities(Plan plan) {
        if (plan.priorityOverrides.isEmpty()) {
            return;
        }
        List<ProductionOrderEntity> changed = new ArrayList<>();
        plan.priorityOverrides.forEach((id, priority) -> {
            ProductionOrderEntity po = plan.orders.get(id);
            if (po != null && !Objects.equals(po.getSchedulingPriority(), priority)) {
                po.setSchedulingPriority(priority);
                changed.add(po);
            }
        });
        if (!changed.isEmpty()) {
            productionOrderRepository.saveAll(changed);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
