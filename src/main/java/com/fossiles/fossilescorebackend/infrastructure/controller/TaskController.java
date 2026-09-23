package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PlanWindowRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DistributionQueueProductionOrderResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialsTaskViewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OrganizerOrderPageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OrganizerProductionOrderResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OplDispatchSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionAutoPlanResult;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionDaySalesSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaskResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaskTicketResponse;
import com.fossiles.fossilescorebackend.application.service.ProductionAutoPlannerService;
import com.fossiles.fossilescorebackend.application.service.ProductionDeskCountService;
import com.fossiles.fossilescorebackend.application.service.TaskDeskHoursService;
import com.fossiles.fossilescorebackend.application.service.OplDispatchSummaryService;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.MaterialConsumptionService;
import com.fossiles.fossilescorebackend.application.service.ProductionTaskGenerationService;
import com.fossiles.fossilescorebackend.application.service.ProductionTaskLifecycleService;
import com.fossiles.fossilescorebackend.application.service.TaskCodeGenerator;
import com.fossiles.fossilescorebackend.application.service.TaskDeskBackfillService;
import com.fossiles.fossilescorebackend.application.service.TaskOrganizerService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderPlanPriority;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionShift;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final double MAX_HOURS_PER_DESK_PER_DAY = ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY;
    private static final double DEFAULT_PRD_TIME_PER_UNIT = ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
    private static final int MAX_DESKS = ProductionPlanningConstants.MAX_DESKS;
    private static final List<String> DESKS_COUNT_CONFIG_KEYS = ProductionPlanningConstants.DESKS_COUNT_CONFIG_KEYS;
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
    private final TaskCodeGenerator taskCodeGenerator;
    private final TaskOrganizerService taskOrganizerService;
    private final TaskDeskBackfillService taskDeskBackfillService;
    private final ProductionTaskLifecycleService productionTaskLifecycleService;
    private final TaskItemMaterialPickRepository taskItemMaterialPickRepository;
    private final ProductionDeskSupervisorRepository productionDeskSupervisorRepository;
    private final ProductionAutoPlannerService productionAutoPlannerService;
    private final OplDispatchSummaryService oplDispatchSummaryService;
    private final ProductionPlanningLock productionPlanningLock;
    private final ProductionDeskCountService productionDeskCountService;
    private final TaskDeskHoursService taskDeskHoursService;
    private final SecurityUtil securityUtil;

    // ==================== CRUD ====================

    /**
     * Convierte una lista de tareas trayendo todos sus items en una sola consulta,
     * en vez de una por tarea. Pensado para los endpoints que devuelven listados.
     */
    private List<TaskResponse> toResponses(List<TaskEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TaskItemEntity>> itemsByTask = taskItemRepository
                .findByTaskIdIn(entities.stream().map(TaskEntity::getId).filter(Objects::nonNull).toList())
                .stream()
                .filter(i -> i.getTaskId() != null)
                .collect(Collectors.groupingBy(TaskItemEntity::getTaskId));

        return entities.stream()
                .map(t -> toResponse(t, itemsByTask.getOrDefault(t.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * Listado completo. La transaccion de solo lectura mantiene una sola sesion de Hibernate
     * para toda la respuesta, de modo que los productos repetidos se resuelven en la cache de
     * primer nivel en vez de una consulta por item.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<TaskResponse>> getAll() {
        return ResponseEntity.ok(toResponses(taskRepository.findAll()));
    }

    // ==================== ORGANIZADOR DE TAREAS ====================
    // Rutas literales ANTES de /{id}, para que "backlog"/"organizer" no choquen con el path variable.

    /**
     * OPs activas con ítems que aún tienen cantidad restante sin tarea, para armar
     * tareas manualmente en el Organizador.
     *
     * @param type OPL (venta en línea), REGULAR (las demás) o ALL
     */
    @GetMapping("/organizer/orders")
    public ResponseEntity<OrganizerOrderPageResponse> getOrganizerOrders(
            @RequestParam(name = "type", defaultValue = "ALL") String type,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "30") int size) throws BusinessException {
        return ResponseEntity.ok(taskOrganizerService.getOrganizerOrders(type, search, page, size));
    }

    /**
     * Crea una tarea manual con cantidades (parciales o totales) de ítems de OP.
     * La tarea nace PENDING; sin mesa/fecha aparece como "Sin asignar" en el tablero.
     */
    @PostMapping("/organizer/manual")
    public ResponseEntity<TaskResponse> createManualTask(
            @RequestBody CreateManualTaskRequest request)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity task = taskOrganizerService.createManualTask(request);
        return ResponseEntity.ok(toResponse(task));
    }

    @PostMapping("/auto-plan")
    public ResponseEntity<ProductionAutoPlanResult> autoPlan(
            @RequestParam(required = false) Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {
        if (productionOrderId != null) {
            return ResponseEntity.ok(productionAutoPlannerService.planOrder(productionOrderId));
        }
        return ResponseEntity.ok(productionAutoPlannerService.planPending());
    }

    @GetMapping("/blocked-leather")
    public ResponseEntity<List<ProductionAutoPlanResult.BlockedLeatherLine>> blockedLeather() {
        return ResponseEntity.ok(productionAutoPlannerService.listBlockedLeather());
    }

    @GetMapping("/day-sales-summary")
    public ResponseEntity<ProductionDaySalesSummaryResponse> daySalesSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(productionAutoPlannerService.daySalesSummary(date));
    }

    /**
     * Ventas en línea pedidas el día anterior a {@code dispatchDate} (hoy por defecto)
     * que deben despacharse esa fecha.
     */
    @GetMapping("/opl-dispatch-summary")
    public ResponseEntity<OplDispatchSummaryResponse> oplDispatchSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dispatchDate) {
        return ResponseEntity.ok(oplDispatchSummaryService.summaryForDispatchDate(dispatchDate));
    }

    /**
     * Backlog del organizador: tareas PENDING atrasadas, sin fecha, o sin mesa
     * (aunque la fecha sea hoy), para retomarlas y reprogramarlas.
     */
    @GetMapping("/organizer/backlog")
    public ResponseEntity<List<TaskResponse>> getPendingBacklog() {
        LocalDate today = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDate();
        List<TaskResponse> tasks = taskRepository.findPendingBacklog(today).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    /**
     * "No terminadas": las que se empezaron un día anterior y siguen abiertas.
     *
     * El backlog de arriba filtra {@code status = 'PENDING'} en igualdad estricta, así que
     * una tarea que alguien empezó y no cerró no salía en ninguna pantalla: es la que el
     * auxiliar no encuentra. Conservan mesa y, por tanto, encargado del día.
     */
    /**
     * La lista por troquelar: tareas pendientes con algun producto sin cortar.
     *
     * <p>Es el paso que va entre el borrador y la cola del dia. Devuelve tareas completas
     * -con sus productos y el estado de troquel de cada uno- porque la pantalla marca por
     * producto, no por tarea.
     *
     * <p>Se apoya en {@code TaskResponse}, que ya lleva {@code dieCutReady} en cada item, asi
     * que el front no necesita un formato nuevo.
     */
    @GetMapping("/organizer/die-cut-pending")
    public ResponseEntity<List<TaskResponse>> getDieCutPending() {
        List<TaskResponse> tasks = taskRepository.findPendingWithUncutItems().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/organizer/unfinished")
    public ResponseEntity<List<TaskResponse>> getUnfinishedCarryOver() {
        LocalDate today = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDate();
        List<TaskResponse> tasks = taskRepository.findUnfinishedCarryOver(today).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(tasks);
    }

    /**
     * "Limpiar mesas": libera mesa de las tareas PENDING para que el usuario reorganice
     * el tablero. No afecta tareas IN_PROGRESS/COMPLETED.
     *
     * @param date si se indica, solo libera la mesa de las tareas PENDING programadas ese
     *             día (mantiene su fecha: "reiniciar tareas del día"). Sin fecha, libera
     *             mesa Y fecha de TODAS las PENDING (reset completo para reorganizar todo).
     */
    @PostMapping("/organizer/clear-desks")
    @Transactional
    public ResponseEntity<Map<String, Object>> clearAllDesks(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int cleared = date != null
                ? taskOrganizerService.clearPendingDeskAssignmentsForDate(date)
                : taskOrganizerService.clearAllPendingDeskAssignments();
        String scope = date != null ? " del " + date : " de su mesa/fecha";
        return ResponseEntity.ok(Map.of(
                "clearedTasks", cleared,
                "message", cleared > 0
                        ? cleared + " tarea(s) liberada(s)" + scope + "."
                        : "No había tareas pendientes con mesa asignada" + (date != null ? " ese día." : ".")));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<TaskResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/{id:\\d+}/ticket")
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

    /**
     * Tareas vivas del centro: todo lo que no esta COMPLETED ni CANCELLED (incluye
     * AWAITING_WAREHOUSE). Mismo criterio que el tablero aplica en el navegador, pero
     * resuelto en SQL. Misma transaccion de solo lectura y misma carga de items en lote
     * que el listado completo.
     */
    @GetMapping("/queue")
    @Transactional(readOnly = true)
    public ResponseEntity<List<TaskResponse>> getQueue() {
        return ResponseEntity.ok(toResponses(taskRepository.findPendingAndInProgressOrdered()));
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

        // Mismo turno que el auto-plan y que plan-window: este endpoint lee la carga del
        // día, elige la mesa menos cargada y escribe encima de lo que leyó.
        productionPlanningLock.acquire();

        int maxConfiguredDesks = getNumDesks();
        int activeDesks = desksCount == null ? maxConfiguredDesks : Math.max(1, Math.min(desksCount, maxConfiguredDesks));

        List<TaskEntity> pendientes = taskRepository.findByScheduledDate(date).stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .filter(t -> !"COMPLETED".equals(t.getStatus()))
                .filter(t -> "PENDING".equals(t.getStatus()))
                .collect(Collectors.toList());

        // Compuerta del troquelado, igual que en plan-window y en el relleno de mesa
        // liberada: redistribuir es repartir mesa, asi que lo que no tiene el corte hecho no
        // entra. Se quedan como estan -si ya tenian mesa, la conservan: esto redistribuye,
        // no desasigna- pero no se les elige mesa nueva.
        List<TaskEntity> candidates = pendientes.stream()
                .filter(t -> Boolean.TRUE.equals(t.getDieCutReady()))
                .collect(Collectors.toList());
        List<TaskEntity> frenadas = pendientes.stream()
                .filter(t -> !Boolean.TRUE.equals(t.getDieCutReady()))
                .collect(Collectors.toList());

        if (pendientes.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "activeDesks", activeDesks,
                    "updatedTasks", 0,
                    "dieCutBlockedTasks", 0,
                    "message", "No hay tareas pendientes para redistribuir en la fecha indicada."));
        }
        if (candidates.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "activeDesks", activeDesks,
                    "updatedTasks", 0,
                    "dieCutBlockedTasks", frenadas.size(),
                    "message", "No se redistribuyó nada: las " + frenadas.size()
                            + " tarea(s) del día esperan troquelado. Marque el corte en «Por troquelar»."));
        }

        // Las horas de venta del día de una sola consulta: el comparador de abajo pregunta
        // por cada tarea y varias veces, así que resolverlo tarea a tarea multiplica los
        // viajes a la base dentro de la transacción.
        //
        // Se piden las de TODAS las pendientes, no solo las repartibles: las horas de las
        // frenadas que ya ocupan mesa tienen que contarse en la carga de esa mesa, o el
        // reparto la veria vacia y le encimaria trabajo hasta pasarse del cupo.
        Map<Long, Double> extraByTaskId = taskDeskHoursService.daySaleExtraByTaskId(
                pendientes.stream().map(TaskEntity::getId).filter(Objects::nonNull).toList());

        Comparator<TaskEntity> byPriorityThenWorkload = Comparator
                .comparing((TaskEntity t) -> -taskDeskHoursService.baseHours(t, extraByTaskId))
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

        // Las frenadas por troquel que YA ocupan mesa siguen ocupandola: esto redistribuye,
        // no desasigna. Si no se sembrara aqui su carga, el reparto veria esas mesas vacias y
        // les encimaria trabajo encima del que ya tienen, pasandose del cupo del dia.
        for (TaskEntity frenada : frenadas) {
            Integer mesa = frenada.getDesk();
            if (mesa != null && deskLoads.containsKey(mesa)) {
                deskLoads.merge(mesa, taskDeskHoursService.baseHours(frenada, extraByTaskId), Double::sum);
            }
        }

        int updated = 0;
        for (TaskEntity task : sorted) {
            int targetDesk = findLeastLoadedDesk(deskLoads);
            double taskHours = taskDeskHoursService.baseHours(task, extraByTaskId);
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
                "dieCutBlockedTasks", frenadas.size(),
                "message", "Redistribucion completada: " + sorted.size() + " tareas repartidas en "
                        + activeDesks + " mesa(s)."
                        + (frenadas.isEmpty() ? ""
                           : " " + frenadas.size() + " tarea(s) quedaron fuera por troquelado pendiente.")));
    }

    /**
     * OPs elegibles para priorizar antes de "Distribuir día": OPV, OPK, OPI con tareas pendientes en la ventana,
     * sin producción iniciada (ninguna tarea IN_PROGRESS de esa OP).
     */
    @GetMapping("/distribution-queue/production-orders")
    public ResponseEntity<List<DistributionQueueProductionOrderResponse>> getDistributionQueueProductionOrders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) Integer horizonDays) {

        int days = horizonDays == null ? 5 : Math.max(1, horizonDays);
        LocalDate selectionEndDate = startDate.plusDays(days - 1);
        List<TaskEntity> pool = taskRepository.findPendingAndInProgressOrdered();

        List<TaskEntity> pendingInWindow = pool.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> {
                    LocalDate sd = t.getScheduledDate();
                    if (sd == null) return true;
                    return !sd.isBefore(startDate) && !sd.isAfter(selectionEndDate);
                })
                .collect(Collectors.toList());

        Set<Long> candidatePoIds = pendingInWindow.stream()
                .map(TaskEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        Set<Long> inProgressPoIds = pool.stream()
                .filter(t -> "IN_PROGRESS".equals(t.getStatus()))
                .map(TaskEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<DistributionQueueProductionOrderResponse> rows = new ArrayList<>();
        for (Long poId : candidatePoIds) {
            if (inProgressPoIds.contains(poId)) {
                continue;
            }
            ProductionOrderEntity po = productionOrderRepository.findById(poId).orElse(null);
            if (po == null) continue;
            String st = po.getStatus() == null ? "" : po.getStatus().trim().toUpperCase();
            if ("COMPLETED".equals(st) || "CANCELLED".equals(st) || "PRODUCED".equals(st)) {
                continue;
            }
            String family = distributionFamilyLabel(po.getOrderType(), po.getCode());
            if (family == null) {
                continue;
            }
            rows.add(DistributionQueueProductionOrderResponse.builder()
                    .id(po.getId())
                    .code(po.getCode())
                    .orderType(po.getOrderType())
                    .family(family)
                    .schedulingPriority(po.getSchedulingPriority())
                    .customerName(po.getCustomerName())
                    .productionStarted(false)
                    .createdAt(po.getCreatedAt())
                    .build());
        }

        // FIFO por defecto: OP creada antes va primero (luego el front puede mezclar con prioridad guardada).
        rows.sort(Comparator
                .comparing(DistributionQueueProductionOrderResponse::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DistributionQueueProductionOrderResponse::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        return ResponseEntity.ok(rows);
    }

    @PostMapping("/plan-window")
    @Transactional
    public ResponseEntity<Map<String, Object>> planWindowTasks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) Integer desksCount,
            @RequestParam(required = false) Integer horizonDays,
            @RequestParam(required = false) Long productionOrderId,
            @RequestParam(required = false, defaultValue = "false") boolean requireDieCut,
            @RequestBody(required = false) PlanWindowRequest planBody) throws BusinessException {

        // Mismo turno que el auto-plan y que el relleno de mesa liberada: los tres
        // leen la carga por mesa y escriben encima de lo que leyeron.
        productionPlanningLock.acquire();

        // Dentro del turno, no antes: estas prioridades deciden el orden de la cola que
        // se reparte tres líneas más abajo, así que escribirlas fuera del candado dejaba
        // una ventana en la que otro hilo podía repartir con el orden viejo.
        mergeSchedulingPrioritiesFromRequest(planBody != null ? planBody.getSchedulingPriorities() : null);

        // Las mesas del día salen de production_desk_count, igual que en el auto-planner
        // (ProductionAutoPlannerService:164). El getNumDesks() de este controlador solo mira
        // system_config y cae a 12, así que los dos planificadores repartían sobre números
        // distintos: el mismo día podía tener 8 mesas para uno y 12 para el otro.
        int maxConfiguredDesks = productionDeskCountService.getDay(startDate).getNumDesks();
        int activeDesks = desksCount == null ? maxConfiguredDesks : Math.max(1, Math.min(desksCount, maxConfiguredDesks));
        int days = horizonDays == null ? 5 : Math.max(1, horizonDays);

        List<TaskEntity> pool = taskRepository.findPendingAndInProgressOrdered();

        // Distribuir mesas: incluir TODAS las PENDING, sin filtrar por scheduledDate (pasado/hoy/futuro/null).
        List<TaskEntity> elegibles = pool.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .filter(t -> productionOrderId == null || Objects.equals(t.getProductionOrderId(), productionOrderId))
                .collect(Collectors.toList());

        List<TaskEntity> candidates = elegibles.stream()
                // Compuerta del troquelado. Solo baja a mesa lo que ya tiene el corte hecho.
                //
                // No cuesta ninguna consulta: die_cut_ready de la tarea es el Y-logico de sus
                // productos, asi que el dato ya viene en la entidad que se acaba de cargar.
                // Comprobarlo por producto aqui habria reintroducido dentro del candado el
                // mismo N+1 que obligo a meter la carga en lote unas lineas mas abajo.
                //
                // El Organizador lo manda encendido (DayQueuePanel -> planTasksWindow). Llega
                // apagado por defecto para no cambiarle el reparto, en silencio, a ningun otro
                // cliente de esta API.
                //
                // Las demas vias que dan mesa aplican la regla sin parametro: rebalance-day y
                // TaskDeskBackfillService filtran por die_cut_ready, los generadores crean las
                // tareas de centro sin mesa, y fijar mesa o mover un producto a mesa la
                // rechazan. Los cinchos nacen marcados y pasan por todas igual.
                .filter(t -> !requireDieCut || Boolean.TRUE.equals(t.getDieCutReady()))
                .collect(Collectors.toList());

        // Cuantas dejo fuera la compuerta. Sin este numero, una tarea que no se reparte por
        // falta de troquel se ve igual que una que no existe: el usuario la busca en el
        // tablero, no la encuentra, y no hay nada que le diga que esta esperando corte.
        int frenadasPorTroquel = elegibles.size() - candidates.size();

        if (candidates.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "startDate", startDate,
                    "activeDesks", activeDesks,
                    "horizonDays", days,
                    "requireDieCut", requireDieCut,
                    "dieCutBlockedTasks", frenadasPorTroquel,
                    "selectedTasks", 0,
                    "updatedTasks", 0,
                    "message", frenadasPorTroquel > 0
                            ? "No se distribuyó nada: las " + frenadasPorTroquel
                              + " tarea(s) pendientes esperan troquelado. Marque el corte en «Por troquelar»."
                            : "No hay tareas PENDIENTES para distribuir a mesas."));
        }

        Set<Long> poIds = candidates.stream()
                .map(TaskEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        List<ProductionOrderEntity> pos = poIds.isEmpty() ? List.of() : productionOrderRepository.findAllById(poIds);
        // Las horas de venta del día de TODAS las candidatas, de una sola consulta. Sin
        // esto, cada llamada a baseHours consultaba task_item por su cuenta, y como se
        // llama desde el comparador y desde dos bucles, una corrida de mil tareas hacía
        // miles de viajes a la base sin soltar el candado: pasaba de segundos a minutos.
        Map<Long, Double> extraByTaskId = taskDeskHoursService.daySaleExtraByTaskId(
                candidates.stream().map(TaskEntity::getId).filter(Objects::nonNull).toList());

        Map<Long, Integer> prioByPo = new HashMap<>();
        Map<Long, LocalDateTime> createdAtByPo = new HashMap<>();
        Map<Long, Boolean> canOvercapByPo = new HashMap<>();
        for (ProductionOrderEntity po : pos) {
            Long id = po.getId();
            if (id == null) continue;
            // El mismo NULL se resolvía aquí con MAX_VALUE (al final de la cola) y en el
            // auto-plan con resolve() (que da 0 a las OPL). La misma orden salía primera
            // en un planificador y última en el otro. Ahora los dos usan resolve().
            prioByPo.put(id, Optional.ofNullable(po.getSchedulingPriority())
                    .orElseGet(() -> ProductionOrderPlanPriority.resolve(po.getOrderType(), po.getCode())));
            createdAtByPo.put(id, po.getCreatedAt() != null ? po.getCreatedAt() : LocalDateTime.MAX);
            canOvercapByPo.put(id, canOvercapDeskDay(po.getOrderType()));
        }
        for (Long id : poIds) {
            prioByPo.putIfAbsent(id, Integer.MAX_VALUE);
            createdAtByPo.putIfAbsent(id, LocalDateTime.MAX);
            canOvercapByPo.putIfAbsent(id, false);
        }

        Comparator<Long> opQueueComparator = Comparator
                .comparing((Long poId) -> prioByPo.getOrDefault(poId, Integer.MAX_VALUE))
                .thenComparing(poId -> createdAtByPo.getOrDefault(poId, LocalDateTime.MAX), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(poId -> poId != null ? poId : Long.MAX_VALUE);

        Map<Long, List<TaskEntity>> tasksByPo = candidates.stream()
                .filter(t -> t.getProductionOrderId() != null)
                .collect(Collectors.groupingBy(TaskEntity::getProductionOrderId));

        // El reparto es por OP, así que una tarea sin OP no entra en ninguna cola. Antes
        // desaparecía en silencio y ni siquiera se contaba (selected se calcula sobre las
        // agrupadas): se reporta aparte para que el usuario sepa que existe.
        List<TaskEntity> tasksWithoutPo = candidates.stream()
                .filter(t -> t.getProductionOrderId() == null)
                .toList();

        Comparator<TaskEntity> withinPoComparator = Comparator
                .comparing((TaskEntity t) -> -taskDeskHoursService.baseHours(t, extraByTaskId))
                .thenComparing(TaskEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getId);

        for (Map.Entry<Long, List<TaskEntity>> e : tasksByPo.entrySet()) {
            e.getValue().sort(withinPoComparator);
        }

        // Cargas iniciales: solo IN_PROGRESS (PENDING se replantea completo según cola).
        // Solo se trabaja lunes a viernes: los fines de semana no entran al mapa, por lo que
        // nunca se eligen como destino (loads==null -> continue en el loop de asignación).
        Map<LocalDate, Map<Integer, Double>> loadsByDate = new HashMap<>();
        for (int d = 0; d < days; d++) {
            LocalDate date = startDate.plusDays(d);
            if (!ProductionPlanningConstants.isWorkday(date)) continue;
            Map<Integer, Double> m = new HashMap<>();
            for (int desk = 1; desk <= activeDesks; desk++) m.put(desk, 0.0);
            loadsByDate.put(date, m);
        }
        // Momento de referencia único para toda la corrida: si cada tarea leyera su propio
        // reloj, dos tareas de la misma mesa se descontarían con instantes distintos.
        LocalDateTime ahora = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDateTime();
        LocalDate primerDiaHabil = siguienteDiaHabil(startDate);
        LocalDate ultimoDiaHorizonte = startDate.plusDays(days - 1L);

        for (TaskEntity t : pool) {
            if (!"IN_PROGRESS".equals(t.getStatus())) continue;
            if (t.getDesk() == null || t.getScheduledDate() == null) continue;
            if (t.getDesk() < 1 || t.getDesk() > activeDesks) continue;

            // Una tarea en curso ocupa su mesa AHORA, aunque su fecha sea vieja o caiga en
            // fin de semana. Antes esos dos casos hacían `continue` y la mesa aparecía
            // vacía: se le encimaban 4 h nuevas sobre trabajo que seguía ahí. Se pliega la
            // carga al primer día hábil del horizonte, sin tocar la fecha de la tarea
            // (reescribirla la sacaría de la vista de atrasos de bodega).
            LocalDate diaDeCarga = t.getScheduledDate();
            if (diaDeCarga.isAfter(ultimoDiaHorizonte)) continue; // futuro legítimo
            Map<Integer, Double> m = loadsByDate.get(diaDeCarga);
            if (m == null) m = loadsByDate.get(primerDiaHabil);
            if (m == null) continue;

            m.put(t.getDesk(),
                    m.getOrDefault(t.getDesk(), 0.0) + taskDeskHoursService.remainingDeskHours(t, ahora, extraByTaskId));
        }

        int updated = 0;
        LocalDate maxAssignedDate = startDate;
        int selected = 0;
        int placed = 0;

        // Lo que no cupo. Antes el `if (chosenDesk != -1)` no tenía rama else: la tarea se
        // quedaba con su mesa y su fecha viejas y la respuesta no lo decía en ningún sitio,
        // así que el usuario no podía distinguir "ya estaba bien" de "no cupo".
        List<Map<String, Object>> notPlaced = new ArrayList<>();

        List<Long> opQueue = tasksByPo.keySet().stream()
                .sorted(opQueueComparator)
                .collect(Collectors.toList());

        // Greedy por OP: agotar OP #1 en todas las mesas posibles antes de pasar a la siguiente.
        for (Long poId : opQueue) {
            List<TaskEntity> poTasks = tasksByPo.getOrDefault(poId, List.of());
            if (poTasks.isEmpty()) continue;
            selected += poTasks.size();

            boolean canOvercapDeskDay = Boolean.TRUE.equals(canOvercapByPo.get(poId));
            for (TaskEntity task : poTasks) {
                double taskHours = taskDeskHoursService.baseHours(task, extraByTaskId);

                LocalDate targetDate = startDate;
                boolean assigned = false;

                // Una venta en línea o de kiosko que ya quedó colocada en un día posterior
                // conserva mesa y día: no se la trae de vuelta al día de la corrida. Antes
                // el tope de un solo día hacía justo lo contrario — cada corrida les
                // reescribía la fecha a hoy y les reasignaba la mesa menos cargada.
                LocalDate fechaPrevia = task.getScheduledDate();
                Integer mesaPrevia = task.getDesk();
                boolean arrastrada = canOvercapDeskDay
                        && mesaPrevia != null
                        && mesaPrevia >= 1 && mesaPrevia <= activeDesks
                        && fechaPrevia != null
                        && fechaPrevia.isAfter(startDate);

                int startDayIdx = 0;
                if (arrastrada) {
                    long desplazamiento = ChronoUnit.DAYS.between(startDate, fechaPrevia);
                    if (desplazamiento < days) startDayIdx = (int) desplazamiento;
                }

                int maxDayIdx = Math.max(0, days - 1);
                for (int dayIdx = startDayIdx; dayIdx <= maxDayIdx && !assigned; dayIdx++) {
                    targetDate = startDate.plusDays(dayIdx);
                    Map<Integer, Double> loads = loadsByDate.get(targetDate);
                    if (loads == null) continue;

                    int chosenDesk = -1;

                    if (arrastrada && targetDate.equals(fechaPrevia)) {
                        // La mesa se conserva porque se deja de elegir, no porque puntúe
                        // mejor: el barrido de abajo se queda siempre con la menos cargada,
                        // y como la carga cambia cada día la tarea iría saltando de mesa.
                        chosenDesk = mesaPrevia;
                    } else {
                        double chosenDeskLoad = Double.POSITIVE_INFINITY;
                        for (int desk = 1; desk <= activeDesks; desk++) {
                            double currentLoad = loads.getOrDefault(desk, 0.0);

                            boolean oversizedSingle = taskHours > MAX_HOURS_PER_DESK_PER_DAY + 1e-9;
                            // El permiso de pasarse del cupo viaja con la tarea arrastrada a
                            // su propia mesa: si se quedara anclado al día de la corrida, una
                            // OPCK lo perdería justo el día en que más lo necesita.
                            boolean canOvercap = canOvercapDeskDay
                                    && (targetDate.equals(startDate)
                                        || (arrastrada && mesaPrevia != null && desk == mesaPrevia));
                            boolean fits =
                                    canOvercap
                                            || (oversizedSingle && currentLoad <= 1e-9)
                                            || (currentLoad + taskHours <= MAX_HOURS_PER_DESK_PER_DAY + 1e-9);

                            if (!fits) continue;
                            if (currentLoad < chosenDeskLoad) {
                                chosenDeskLoad = currentLoad;
                                chosenDesk = desk;
                            }
                        }
                    }

                    if (chosenDesk != -1) {
                        loads.put(chosenDesk, loads.getOrDefault(chosenDesk, 0.0) + taskHours);
                        maxAssignedDate = maxAssignedDate.isBefore(targetDate) ? targetDate : maxAssignedDate;

                        if (!Objects.equals(task.getDesk(), chosenDesk) || !Objects.equals(task.getScheduledDate(), targetDate)) {
                            task.setDesk(chosenDesk);
                            task.setScheduledDate(targetDate);
                            taskRepository.save(task);
                            updated++;
                        }
                        assigned = true;
                        placed++;
                    }
                }

                if (!assigned) {
                    String motivo = arrastrada
                            ? "Viene arrastrada de " + fechaPrevia + " en la mesa " + mesaPrevia
                              + " y esa mesa ya no tiene hueco en el horizonte."
                            : "No cupo en las " + activeDesks + " mesas dentro de los "
                              + days + " día(s) del horizonte.";

                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("taskId", task.getId());
                    fila.put("taskCode", task.getCode());
                    fila.put("productionOrderCode", task.getProductionOrderCode());
                    fila.put("hours", taskHours);
                    fila.put("currentDesk", task.getDesk());
                    fila.put("currentDate", task.getScheduledDate());
                    // Ahora todas tienen día siguiente: las de venta en línea y kiosko ya no
                    // están ancladas al día de la corrida.
                    fila.put("nextDayCandidate", siguienteDiaHabil(startDate.plusDays(days)));
                    fila.put("keepsDesk", arrastrada ? mesaPrevia : null);
                    fila.put("reason", motivo);
                    notPlaced.add(fila);
                }
            }
        }

        for (TaskEntity task : tasksWithoutPo) {
            selected++;
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("taskId", task.getId());
            fila.put("taskCode", task.getCode());
            fila.put("productionOrderCode", null);
            fila.put("hours", taskDeskHoursService.baseHours(task, extraByTaskId));
            fila.put("currentDesk", task.getDesk());
            fila.put("currentDate", task.getScheduledDate());
            fila.put("nextDayCandidate", null);
            fila.put("reason", "Sin orden de producción: el reparto va por OP y esta tarea "
                    + "no pertenece a ninguna.");
            notPlaced.add(fila);
        }

        // Las frenadas por troquel no son "no cupieron": no llegaron a competir por mesa. Si
        // no se dicen aparte, el usuario las cuenta como un problema de capacidad y agrega
        // mesas que no hacen falta, cuando lo que falta es cortar.
        String avisoTroquel = frenadasPorTroquel > 0
                ? " " + frenadasPorTroquel + " tarea(s) quedaron fuera por troquelado pendiente."
                : "";

        String message = (notPlaced.isEmpty()
                ? "Distribución por OP completada: " + placed + " de " + selected
                  + " tarea(s) colocada(s) desde " + startDate
                  + " (horizonte " + days + " día(s))."
                : "Distribución por OP parcial: " + placed + " de " + selected
                  + " tarea(s) colocada(s). " + notPlaced.size()
                  + " no cupo/cupieron en el horizonte de " + days + " día(s).")
                + avisoTroquel;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", startDate);
        body.put("activeDesks", activeDesks);
        body.put("horizonDays", days);
        // Que el cliente sepa si la corrida aplico la compuerta: sin esto, un reparto que
        // dejo fuera media orden por falta de troquel se ve igual que uno que no la aplico.
        body.put("requireDieCut", requireDieCut);
        body.put("dieCutBlockedTasks", frenadasPorTroquel);
        body.put("selectedTasks", selected);
        body.put("placedTasks", placed);
        body.put("updatedTasks", updated);
        body.put("notPlacedTasks", notPlaced.size());
        body.put("notPlaced", notPlaced);
        body.put("maxAssignedDate", maxAssignedDate);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }

    /**
     * Primer día hábil desde {@code desde} inclusive. Se usa para decirle al usuario a qué
     * día se iría el trabajo que no cupo en el horizonte, sin proponerle un fin de semana.
     * El tope de 14 vueltas es una guarda: sábado y domingo nunca encadenan tantos.
     */
    private LocalDate siguienteDiaHabil(LocalDate desde) {
        LocalDate cursor = desde;
        for (int i = 0; i < 14 && !ProductionPlanningConstants.isWorkday(cursor); i++) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    @PutMapping("/{id:\\d+}/status")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<TaskResponse> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        String newStatus = body.get("status");
        if (newStatus == null || !isValidStatus(newStatus)) {
            throw new BusinessException("Invalid status. Must be: PENDING, IN_PROGRESS, AWAITING_WAREHOUSE, COMPLETED, CANCELLED");
        }

        String effectiveStatus = newStatus;
        if ("COMPLETED".equals(newStatus) && productionTaskLifecycleService.shouldDeferCompletionToWarehouse(entity)) {
            effectiveStatus = ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE;
        }

        if (!canTransitionStatus(entity.getStatus(), effectiveStatus)) {
            throw new BusinessException("Transición de estado no permitida: " + entity.getStatus() + " -> " + effectiveStatus);
        }
        if ("COMPLETED".equals(effectiveStatus) && entity.getStartedAt() == null) {
            throw new BusinessException("No se puede completar una tarea que no ha sido iniciada.");
        }

        Integer deskToFreeAfterComplete = null;
        LocalDate backfillAnchorDate = null;
        if ("COMPLETED".equals(effectiveStatus)
                || ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(effectiveStatus)) {
            deskToFreeAfterComplete = entity.getDesk();
            backfillAnchorDate = entity.getScheduledDate() != null
                    ? entity.getScheduledDate()
                    : ZonedDateTime.now(GUATEMALA_ZONE).toLocalDate();
        }

        if ("IN_PROGRESS".equals(effectiveStatus)) {
            splitBlockedItemsIntoPendingTask(entity);
        }

        entity.setStatus(effectiveStatus);

        // Time tracking
        if ("IN_PROGRESS".equals(effectiveStatus) && entity.getStartedAt() == null) {
            LocalDateTime gtNow = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDateTime();
            entity.setStartedAt(gtNow);
            entity.setStartTime(gtNow.toLocalTime().format(HOUR_MINUTE_FORMATTER));
        }
        if ("COMPLETED".equals(effectiveStatus) && entity.getCompletedAt() == null) {
            LocalDateTime gtNow = ZonedDateTime.now(GUATEMALA_ZONE).toLocalDateTime();
            entity.setCompletedAt(gtNow);
            // Tiempo realmente trabajado: solo lo que cae dentro de la jornada.
            if (entity.getStartedAt() != null) {
                long minutes = ProductionShift.workingMinutesBetween(
                        entity.getStartedAt(), entity.getCompletedAt());
                entity.setActualDurationMinutes((int) minutes);
            }

            // Al completar: desasignar de mesa (para liberar la cola de mesas),
            // pero conservar el historial de donde se trabajo.
            if (entity.getWorkedDesk() == null && entity.getDesk() != null) {
                entity.setWorkedDesk(entity.getDesk());
            }
            entity.setDesk(null);
        }
        if (ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(effectiveStatus)) {
            if (entity.getWorkedDesk() == null && entity.getDesk() != null) {
                entity.setWorkedDesk(entity.getDesk());
            }
            entity.setDesk(null);
        }
        if ("PENDING".equals(effectiveStatus)) {
            entity.setStartedAt(null);
            entity.setCompletedAt(null);
            entity.setActualDurationMinutes(null);
            entity.setStartTime(null);
        }

        TaskEntity updated = taskRepository.save(entity);
        if (deskToFreeAfterComplete != null && backfillAnchorDate != null
                && ("COMPLETED".equals(effectiveStatus)
                || ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(effectiveStatus))) {
            taskDeskBackfillService.backfillFreedDeskAfterCompletion(deskToFreeAfterComplete, backfillAnchorDate, getNumDesks());
        }
        productionTaskLifecycleService.syncProductionOrderStatusFromTasks(updated.getProductionOrderId());
        return ResponseEntity.ok(toResponse(updated));
    }

    /** Visible en el paquete (no private) para que la prueba de la compuerta pueda construirlo. */
    record MoveTaskItemRequest(Long taskItemId, Integer targetDesk, String targetDate) {}

    private record MoveTaskItemResult(
            Long taskItemId,
            TaskResponse sourceTask,
            Long sourceTaskDeletedId,
            TaskResponse targetTask
    ) {}

    @PutMapping("/move-item")
    @Transactional
    public ResponseEntity<MoveTaskItemResult> moveTaskItem(@RequestBody MoveTaskItemRequest req)
            throws ResourceNotFoundException, BusinessException {
        if (req == null || req.taskItemId() == null) {
            throw new BusinessException("taskItemId es requerido");
        }

        TaskItemEntity item = taskItemRepository.findById(req.taskItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", req.taskItemId()));

        TaskEntity sourceTask = taskRepository.findById(item.getTaskId())
                .orElseThrow(() -> new ResourceNotFoundException("Task", item.getTaskId()));

        if ("CANCELLED".equals(sourceTask.getStatus()) || "COMPLETED".equals(sourceTask.getStatus())) {
            throw new BusinessException("No se puede redistribuir items de una tarea cancelada/completada.");
        }
        if ("IN_PROGRESS".equals(sourceTask.getStatus())) {
            throw new BusinessException("No se puede redistribuir items de una tarea en progreso.");
        }

        LocalDate targetDate = null;
        if (req.targetDate() != null && !req.targetDate().isBlank()) {
            try {
                targetDate = LocalDate.parse(req.targetDate());
            } catch (Exception e) {
                throw new BusinessException("targetDate inválida (use yyyy-MM-dd)");
            }
        }
        if (!ProductionPlanningConstants.isWorkday(targetDate)) {
            throw new BusinessException("Solo se puede mover a mesa de lunes a viernes: " + targetDate + " es fin de semana.");
        }

        Integer targetDesk = req.targetDesk();
        // Compuerta del troquelado. Mover un producto a una mesa es bajarlo a mesa, y aqui se
        // mira el PRODUCTO y no la tarea: es justo el caso que el troquelado por producto vino
        // a resolver -de cinco productos hay tres cortados- y lo que se mueve es uno solo.
        //
        // Mover a la bandeja sin mesa (targetDesk null) se permite: eso es sacar de mesa, u
        // ordenar por dia, y no mete trabajo sin cortar en el tablero.
        if (targetDesk != null && !Boolean.TRUE.equals(item.getDieCutReady())) {
            throw new BusinessException(
                    "No se puede mover " + descripcionItem(item) + " a la mesa " + targetDesk
                    + " sin troquelar. Marque el corte en «Por troquelar».");
        }
        TaskEntity targetTask = resolveOrCreateTargetTask(sourceTask, targetDesk, targetDate);

        Long sourceTaskId = sourceTask.getId();
        Long targetTaskId = targetTask.getId();
        if (Objects.equals(sourceTaskId, targetTaskId)) {
            TaskResponse same = toResponse(sourceTask);
            return ResponseEntity.ok(new MoveTaskItemResult(item.getId(), same, null, same));
        }

        item.setTaskId(targetTaskId);
        taskItemRepository.save(item);

        TaskResponse sourceResponse = null;
        Long sourceDeletedId = null;
        List<TaskItemEntity> sourceItems = taskItemRepository.findByTaskId(sourceTaskId);
        if (!sourceItems.isEmpty()) {
            recalculateTaskTotals(sourceTask, sourceItems);
            sourceTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(sourceTask));
            sourceTask.setMaterialsDeliveredAt(Boolean.TRUE.equals(sourceTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);
            sourceResponse = toResponse(taskRepository.save(sourceTask));
        } else {
            boolean safeToDelete = "PENDING".equals(sourceTask.getStatus())
                    && sourceTask.getStartedAt() == null
                    && sourceTask.getCompletedAt() == null;
            if (safeToDelete) {
                taskRepository.deleteById(sourceTaskId);
                sourceDeletedId = sourceTaskId;
            } else {
                sourceTask.setQuantity(0);
                sourceTask.setEstimatedHours(0.0);
                sourceResponse = toResponse(taskRepository.save(sourceTask));
            }
        }

        List<TaskItemEntity> targetItems = taskItemRepository.findByTaskId(targetTaskId);
        recalculateTaskTotals(targetTask, targetItems);
        targetTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(targetTask));
        targetTask.setMaterialsDeliveredAt(Boolean.TRUE.equals(targetTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        TaskResponse targetResponse = toResponse(taskRepository.save(targetTask));

        return ResponseEntity.ok(new MoveTaskItemResult(
                item.getId(),
                sourceResponse,
                sourceDeletedId,
                targetResponse
        ));
    }

    /**
     * Saca de la tarea los productos que AUN NO se troquelaron, a una tarea hermana.
     *
     * <p>Es lo que permite que una orden baje a mesa a medias: los cortados se quedan y la
     * tarea queda troquelada entera, los que faltan se van a una hermana que espera su corte.
     *
     * <p><b>La hermana nace sin dia y sin mesa.</b> Ademas de ser lo correcto, evita una
     * trampa de {@link #resolveOrCreateTargetTask}: con fecha, ese metodo REUTILIZA una tarea
     * PENDING de la misma orden en esa mesa y dia, y si esa ya estuviera troquelada le
     * entrarian productos sin cortar. Con la fecha en null nunca busca, siempre crea.
     *
     * <p>Se fuerza {@code dieCutReady = false} aunque hoy el heredado ya seria falso, porque
     * ese metodo copia los flags del origen y depender del orden de las operaciones es fragil.
     *
     * <p>No hace nada si estan todos cortados o ninguno lo esta: la tarea ya es homogenea.
     */
    @PostMapping("/{id:\\d+}/die-cut/split-uncut")
    @Transactional
    public ResponseEntity<Map<String, Object>> splitUncutDieCutItems(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity sourceTask = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        // Mismas guardas que move-item: una tarea que ya arranco no se reparte en dos.
        if ("CANCELLED".equals(sourceTask.getStatus()) || "COMPLETED".equals(sourceTask.getStatus())) {
            throw new BusinessException("No se puede separar productos de una tarea cancelada o completada.");
        }
        if ("IN_PROGRESS".equals(sourceTask.getStatus())) {
            throw new BusinessException("No se puede separar productos de una tarea en progreso.");
        }

        List<TaskItemEntity> items = taskItemRepository.findByTaskId(sourceTask.getId());
        List<TaskItemEntity> sinCortar = items.stream()
                .filter(it -> !Boolean.TRUE.equals(it.getDieCutReady()))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", sourceTask.getId());
        body.put("taskCode", sourceTask.getCode());

        if (sinCortar.isEmpty() || sinCortar.size() == items.size()) {
            body.put("split", false);
            body.put("movedItems", 0);
            body.put("siblingTaskId", null);
            body.put("message", sinCortar.isEmpty()
                    ? "Todos los productos estan troquelados: no hay nada que separar."
                    : "Ningun producto esta troquelado todavia: la tarea se queda entera.");
            return ResponseEntity.ok(body);
        }

        TaskEntity hermana = resolveOrCreateTargetTask(sourceTask, null, null);
        hermana.setDieCutReady(false);
        hermana.setDieCutDate(null);
        hermana.setObservations("Separada de " + sourceTask.getCode() + " por troquelado pendiente");
        hermana = taskRepository.save(hermana);

        for (TaskItemEntity it : sinCortar) {
            it.setTaskId(hermana.getId());
            taskItemRepository.save(it);
        }

        // Misma contabilidad que move-item: recalcular totales y consolidar los flags de fase
        // en las dos tareas. Sin esto, la de origen se queda con las horas de los productos
        // que ya no lleva y el reparto le reservaria mesa de mas.
        List<TaskItemEntity> itemsOrigen = taskItemRepository.findByTaskId(sourceTask.getId());
        recalculateTaskTotals(sourceTask, itemsOrigen);
        sourceTask.setDieCutReady(areTaskItemsDieCut(sourceTask));
        sourceTask.setDieCutDate(Boolean.TRUE.equals(sourceTask.getDieCutReady()) ? LocalDate.now() : null);
        sourceTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(sourceTask));
        sourceTask.setMaterialsDeliveredAt(
                Boolean.TRUE.equals(sourceTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        taskRepository.save(sourceTask);

        List<TaskItemEntity> itemsHermana = taskItemRepository.findByTaskId(hermana.getId());
        recalculateTaskTotals(hermana, itemsHermana);
        hermana.setDieCutReady(areTaskItemsDieCut(hermana));
        hermana.setMaterialsDelivered(areRequiredTaskItemsDelivered(hermana));
        hermana.setMaterialsDeliveredAt(
                Boolean.TRUE.equals(hermana.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        taskRepository.save(hermana);

        body.put("split", true);
        body.put("movedItems", sinCortar.size());
        body.put("siblingTaskId", hermana.getId());
        body.put("siblingTaskCode", hermana.getCode());
        body.put("message", sinCortar.size() + " producto(s) sin troquelar pasaron a "
                + hermana.getCode() + "; " + itemsOrigen.size() + " quedaron en "
                + sourceTask.getCode() + " listos para mesa.");
        return ResponseEntity.ok(body);
    }

    private TaskEntity resolveOrCreateTargetTask(TaskEntity sourceTask, Integer targetDesk, LocalDate targetDate) throws BusinessException {
        if (sourceTask == null) throw new BusinessException("Task origen inválida");
        Long poId = sourceTask.getProductionOrderId();
        if (poId == null) throw new BusinessException("La tarea origen no tiene productionOrderId");

        // Buscar una tarea PENDING compatible en la mesa/fecha destino (mesa o sin mesa).
        if (targetDate != null) {
            List<TaskEntity> base = targetDesk != null
                    ? taskRepository.findByDeskAndScheduledDate(targetDesk, targetDate)
                    : taskRepository.findByDeskIsNullAndScheduledDate(targetDate);
            List<TaskEntity> candidates = base.stream()
                    .filter(t -> Objects.equals(t.getProductionOrderId(), poId))
                    .filter(t -> "PENDING".equals(t.getStatus()))
                    .toList();
            if (!candidates.isEmpty()) {
                return candidates.get(0);
            }
        }

        // Crear nueva tarea destino
        String code = generateTaskCode();
        TaskEntity dest = TaskEntity.builder()
                .code(code)
                .productionOrderId(sourceTask.getProductionOrderId())
                .productionOrderCode(sourceTask.getProductionOrderCode())
                .deliveryDate(sourceTask.getDeliveryDate())
                .priority(sourceTask.getPriority())
                .observations("Redistribuida manualmente desde " + sourceTask.getCode())
                .desk(targetDesk)
                .scheduledDate(targetDate)
                .startTime(null)
                .startedAt(null)
                .completedAt(null)
                .actualDurationMinutes(null)
                .status("PENDING")
                .leatherDelivered(sourceTask.getLeatherDelivered())
                .leatherDeliveredAt(sourceTask.getLeatherDeliveredAt())
                .dieCutReady(sourceTask.getDieCutReady())
                .dieCutDate(sourceTask.getDieCutDate())
                .materialsDelivered(false)
                .materialsDeliveredAt(null)
                .build();

        return taskRepository.save(dest);
    }

    @PutMapping("/{id:\\d+}/waste")
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

    @PutMapping("/{id:\\d+}/leather-delivery")
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

    @PutMapping("/{id:\\d+}/leather-delivery/item/{taskItemId}")
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

    /**
     * Marca o desmarca el troquelado de UN producto de la tarea.
     *
     * <p>Clon de {@link #setTaskItemLeatherDelivery}, con una diferencia que importa: la
     * compuerta del cuero se comprueba con un O y no con el flag del item a secas.
     *
     * <p><b>Por que el O.</b> El cuero se entrega por orden: cuando bodega lo registra,
     * {@code LeatherInventoryService.markLeatherDeliveredForProductionOrder} marca la TAREA y
     * no baja a sus productos. Exigir el flag del item dejaria sin poder troquelarse a toda
     * tarea con el cuero ya entregado, que son la mayoria, asi que se acepta el de la tarea,
     * igual que hace el DTO al pintarlo.
     *
     * <p>El flag de la tarea pasa a ser el Y-logico de sus productos, igual que el cuero.
     */
    @PutMapping("/{id:\\d+}/die-cut/item/{taskItemId}")
    public ResponseEntity<TaskResponse> setTaskItemDieCut(
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

        boolean ready = body.get("dieCutReady") != null && Boolean.parseBoolean(body.get("dieCutReady").toString());

        if (ready && !hasLeatherForItem(entity, item)) {
            throw new BusinessException(
                    "No se puede troquelar " + descripcionItem(item) + " sin entrega de cuero.");
        }
        if (!ready && (Boolean.TRUE.equals(entity.getMaterialsDelivered())
                || "IN_PROGRESS".equals(entity.getStatus())
                || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar troquelado porque la tarea ya avanzó de fase.");
        }

        item.setDieCutReady(ready);
        item.setDieCutDate(ready ? LocalDate.now() : null);
        taskItemRepository.save(item);

        entity.setDieCutReady(areTaskItemsDieCut(entity));
        entity.setDieCutDate(Boolean.TRUE.equals(entity.getDieCutReady()) ? LocalDate.now() : null);
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    /**
     * Fija para que dia esta previsto troquelar un producto.
     *
     * <p>No toca {@code dieCutDate}, que dice cuando se marco. Esta dice cuando toca, y es la
     * que agrupa el listado de pendientes por troquelar.
     */
    @PutMapping("/{id:\\d+}/die-cut/item/{taskItemId}/planned-date")
    public ResponseEntity<TaskResponse> setTaskItemDieCutPlannedDate(
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

        Object raw = body.get("plannedDate");
        LocalDate planned = null;
        if (raw != null && !raw.toString().isBlank()) {
            try {
                planned = LocalDate.parse(raw.toString());
            } catch (Exception e) {
                throw new BusinessException("plannedDate inválida (use yyyy-MM-dd).");
            }
            if (!ProductionPlanningConstants.isWorkday(planned)) {
                throw new BusinessException(
                        "Solo se troquela de lunes a viernes: " + planned + " es fin de semana.");
            }
        }

        item.setDieCutPlannedDate(planned);
        taskItemRepository.save(item);
        return ResponseEntity.ok(toResponse(entity));
    }

    @PutMapping("/{id:\\d+}/materials-delivery")
    public ResponseEntity<TaskResponse> setMaterialsDelivery(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        boolean delivered = body.get("delivered") != null && Boolean.parseBoolean(body.get("delivered").toString());
        boolean force = body.get("force") != null && Boolean.parseBoolean(body.get("force").toString());
        if (!delivered && ("IN_PROGRESS".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus()))) {
            throw new BusinessException("No se puede desmarcar materiales entregados cuando la tarea ya está en proceso o completada.");
        }

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(entity.getId());
        if (!taskItems.isEmpty()) {
            if (delivered) {
                ProductionOrderEntity order = entity.getProductionOrderId() != null
                        ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                        : null;
                Boolean orderConsumed = order != null ? order.getMaterialsConsumed() : null;

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
                        if (materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(
                                orderConsumed, entity.getProductionOrderId(), item.getId())) {
                            materialConsumptionService.consumeMaterialsForTaskItem(entity.getId(), item.getId(), force);
                        }
                        item.setMaterialsDelivered(true);
                        item.setMaterialsDeliveredAt(LocalDateTime.now());
                        taskItemRepository.save(item);
                    }
                }
            } else {
                // Undeliver clears flags/picks only — does NOT reverse material kardex/consumption.
                for (TaskItemEntity item : taskItems) {
                    if (isTaskItemRequiresMaterials(item)) {
                        if (item.getId() != null) {
                            taskItemMaterialPickRepository.deleteByTaskItemId(item.getId());
                        }
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
                    materialConsumptionService.consumeMaterialsForTask(entity.getId(), force);
                }
            }
            entity.setMaterialsDelivered(delivered);
            entity.setMaterialsDeliveredAt(delivered ? LocalDateTime.now() : null);
        }
        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id:\\d+}/materials-delivery/item/{taskItemId}")
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
        boolean force = body.get("force") != null && Boolean.parseBoolean(body.get("force").toString());
        int consumedLines = 0;
        if (!isTaskItemRequiresMaterials(item)) {
            item.setMaterialsDelivered(true);
            item.setMaterialsDeliveredAt(item.getMaterialsDeliveredAt() != null ? item.getMaterialsDeliveredAt() : LocalDateTime.now());
        } else if (delivered) {
            if (!Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                ProductionOrderEntity order = entity.getProductionOrderId() != null
                        ? productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null)
                        : null;
                Boolean orderConsumed = order != null ? order.getMaterialsConsumed() : null;
                // Skip deduct when already consumed at OP level OR for this item (prevents double MP deduct).
                if (materialConsumptionService.shouldConsumeOnItemMaterialsDelivery(
                        orderConsumed, entity.getProductionOrderId(), item.getId())) {
                    Map<String, Object> consumptionResult = materialConsumptionService.consumeMaterialsForTaskItem(
                            entity.getId(), item.getId(), force);
                    Object rawCount = consumptionResult.get("materialsConsumed");
                    if (rawCount instanceof Number) {
                        consumedLines = ((Number) rawCount).intValue();
                    }
                }
            }
            item.setMaterialsDelivered(true);
            item.setMaterialsDeliveredAt(LocalDateTime.now());
        } else {
            if ("IN_PROGRESS".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus())) {
                throw new BusinessException("No se puede desmarcar materiales cuando la tarea ya está en proceso o completada.");
            }
            // Undeliver clears delivery flags/picks only — does NOT reverse material kardex/consumption.
            taskItemMaterialPickRepository.deleteByTaskItemId(item.getId());
            item.setMaterialsDelivered(false);
            item.setMaterialsDeliveredAt(null);
        }

        taskItemRepository.save(item);
        entity.setMaterialsDelivered(areRequiredTaskItemsDelivered(entity));
        entity.setMaterialsDeliveredAt(Boolean.TRUE.equals(entity.getMaterialsDelivered()) ? LocalDateTime.now() : null);
        TaskEntity updated = taskRepository.save(entity);
        TaskResponse response = toResponse(updated);
        response.setLastItemMaterialsConsumed(consumedLines);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id:\\d+}/schedule")
    public ResponseEntity<TaskResponse> scheduleTask(@PathVariable Long id, @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity entity = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        if (body.containsKey("scheduledDate")) {
            String dateStr = (String) body.get("scheduledDate");
            LocalDate scheduledDate = dateStr != null && !dateStr.isEmpty() ? LocalDate.parse(dateStr) : null;
            if (!ProductionPlanningConstants.isWorkday(scheduledDate)) {
                throw new BusinessException("Solo se puede programar de lunes a viernes: " + scheduledDate + " es fin de semana.");
            }
            entity.setScheduledDate(scheduledDate);
        }
        if (body.containsKey("desk")) {
            Object deskVal = body.get("desk");
            Integer nuevaMesa = deskVal != null ? Integer.parseInt(deskVal.toString()) : null;
            // Compuerta del troquelado, tambien a mano. Los repartos automaticos ya no dan
            // mesa sin corte; si esta puerta quedara abierta, la regla seria un consejo y no
            // una regla, y bastaria arrastrar la tarea en el tablero para saltarsela.
            //
            // Quitar la mesa (null) se permite siempre: sacar del tablero nunca es el problema.
            if (nuevaMesa != null && !Boolean.TRUE.equals(entity.getDieCutReady())) {
                throw new BusinessException(
                        "No se puede poner en mesa " + nuevaMesa + " la tarea " + entity.getCode()
                        + " porque tiene producto sin troquelar. Marque el corte en «Por troquelar».");
            }
            entity.setDesk(nuevaMesa);
        }
        if (body.containsKey("deliveryDate")) {
            String dateStr = (String) body.get("deliveryDate");
            entity.setDeliveryDate(dateStr != null && !dateStr.isEmpty() ? LocalDate.parse(dateStr) : null);
        }
        // startTime is now automatic when task moves to IN_PROGRESS (Guatemala time).

        TaskEntity updated = taskRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @GetMapping("/{id:\\d+}/day-sale-candidates")
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

            if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                continue;
            }

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

    @PutMapping("/{id:\\d+}/day-sale-items")
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
            if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                throw new BusinessException("Los productos cincho se gestionan en la vista de Cinchos, no como extra de venta del día.");
            }
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

    @PutMapping("/{id:\\d+}/die-cut")
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

    @DeleteMapping("/{id:\\d+}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        TaskEntity entity = taskRepository.findById(id).orElse(null);
        if (entity == null) {
            throw new ResourceNotFoundException("Task", id);
        }
        Long productionOrderId = entity.getProductionOrderId();
        taskItemRepository.deleteByTaskId(id);
        taskRepository.deleteById(id);
        productionTaskLifecycleService.syncProductionOrderStatusFromTasks(productionOrderId);
        return ResponseEntity.noContent().build();
    }

    // ==================== MATERIALS VIEW ====================

    /**
     * Vista materiales: “qué produce / despachar” por día (zona Guatemala).
     * <ul>
     *   <li>Default ({@code scheduleDay=false}, {@code includeDelivered=false}):
     *       tareas del día programado + backlog de hoy, solo pendientes de materiales
     *       ({@link #isPendingMaterialsViewTask}).</li>
     *   <li>{@code includeDelivered=true}: tareas con entrega de materiales registrada en {@code date}
     *       (por timestamp del día).</li>
     *   <li>{@code scheduleDay=true}: todas las tareas del día de trabajo (programadas + backlog si es hoy),
     *       pendientes y ya entregadas (no canceladas).</li>
     * </ul>
     */
    @GetMapping("/materials-view")
    @Transactional(readOnly = true)
    public ResponseEntity<List<MaterialsTaskViewResponse>> getMaterialsView(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "includeDelivered", defaultValue = "false") boolean includeDelivered,
            @RequestParam(name = "scheduleDay", defaultValue = "false") boolean scheduleDay) {

        LocalDate targetDate = date != null ? date : LocalDate.now(GUATEMALA_ZONE);

        if (scheduleDay) {
            LocalDate day = date != null ? date : LocalDate.now(GUATEMALA_ZONE);
            List<TaskEntity> tasks = collectTasksScheduledForMaterialsDay(day);
            List<MaterialsTaskViewResponse> responses = tasks.stream()
                    .filter(t -> !"CANCELLED".equals(t.getStatus()))
                    .map(this::toMaterialsView)
                    .filter(this::hasMaterialsDeliveryLines)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(responses);
        }

        if (includeDelivered) {
            LocalDateTime start = targetDate.atStartOfDay();
            LocalDateTime end = targetDate.plusDays(1).atStartOfDay();
            List<TaskEntity> delivered = taskRepository.findTasksWithMaterialsDeliveredBetween(start, end);
            List<MaterialsTaskViewResponse> responses = delivered.stream()
                    .map(this::toMaterialsView)
                    .filter(this::hasMaterialsDeliveryLines)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(responses);
        }

        // Pendientes del día: scheduledDate = date (+ backlog activo si date es hoy) y aún sin materiales.
        List<TaskEntity> tasks = mergeActiveMaterialsBacklogForToday(
                targetDate, taskRepository.findByScheduledDate(targetDate));

        List<MaterialsTaskViewResponse> responses = tasks.stream()
                .filter(this::isPendingMaterialsViewTask)
                .map(this::toMaterialsView)
                .filter(this::hasMaterialsDeliveryLines)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * Vista materiales para todas las tareas de una orden de producción específica.
     */
    @GetMapping("/materials-view/production-order/{productionOrderId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<MaterialsTaskViewResponse>> getMaterialsViewByOrder(
            @PathVariable Long productionOrderId,
            @RequestParam(name = "includeDelivered", defaultValue = "false") boolean includeDelivered) {

        List<TaskEntity> tasks = findTasksLinkedToProductionOrder(productionOrderId);
        List<MaterialsTaskViewResponse> responses = tasks.stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .filter(t -> includeDelivered || isPendingMaterialsViewTask(t))
                .map(this::toMaterialsView)
                .filter(this::hasMaterialsDeliveryLines)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    private boolean hasMaterialsDeliveryLines(MaterialsTaskViewResponse view) {
        return view != null
                && view.getProducts() != null
                && !view.getProducts().isEmpty();
    }

    /**
     * Marca o desmarca una línea de la receta (BOM) como preparada/despachada para un ítem de tarea.
     */
    @PutMapping("/{taskId:\\d+}/materials-pick/item/{taskItemId}/material/{materialId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> setTaskItemMaterialPick(
            @PathVariable Long taskId,
            @PathVariable Long taskItemId,
            @PathVariable Long materialId,
            @RequestBody Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        TaskItemEntity item = taskItemRepository.findById(taskItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", taskItemId));
        if (!Objects.equals(item.getTaskId(), task.getId())) {
            throw new BusinessException("El item no pertenece a la tarea indicada.");
        }
        if (Boolean.TRUE.equals(item.getMaterialsDelivered())) {
            throw new BusinessException("No se puede modificar la receta luego de entregar materiales del producto.");
        }
        boolean picked = body.get("picked") != null && Boolean.parseBoolean(body.get("picked").toString());
        if (!picked && ("IN_PROGRESS".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus()))) {
            throw new BusinessException("No se puede desmarcar material cuando la tarea ya está en proceso o completada.");
        }
        if (!materialBelongsToTaskItemRecipe(item, materialId)) {
            throw new BusinessException("El material no pertenece a la receta de este producto.");
        }
        Long userId = securityUtil.getCurrentUserId();
        Optional<TaskItemMaterialPickEntity> existing = taskItemMaterialPickRepository
                .findByTaskItemIdAndMaterialId(taskItemId, materialId);
        if (picked) {
            TaskItemMaterialPickEntity pick = existing.orElseGet(() -> TaskItemMaterialPickEntity.builder()
                    .taskItemId(taskItemId)
                    .materialId(materialId)
                    .picked(false)
                    .build());
            pick.setPicked(true);
            pick.setPickedAt(LocalDateTime.now());
            pick.setPickedBy(userId);
            taskItemMaterialPickRepository.save(pick);
        } else {
            existing.ifPresent(taskItemMaterialPickRepository::delete);
        }
        return ResponseEntity.ok(Map.of("ok", true, "picked", picked));
    }

    private boolean materialBelongsToTaskItemRecipe(TaskItemEntity item, Long materialId) {
        if (item.getProductId() == null || materialId == null) {
            return false;
        }
        List<BomEntity> boms = bomRepository.findByProductIdAndStatus(item.getProductId(), "A");
        Long colorId = item.getColorId();
        BomEntity matchedBom = boms.stream()
                .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                .findFirst()
                .orElse(boms.isEmpty() ? null : boms.get(0));
        if (matchedBom == null) {
            return false;
        }
        return bomItemRepository.findByBomId(matchedBom.getId()).stream()
                .anyMatch(bi -> materialId.equals(bi.getMaterialId()));
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
                    .filter(p -> !Boolean.FALSE.equals(p.getRequiresMaterials()))
                    .collect(Collectors.toList());
        } else if (task.getProductId() != null) {
            TaskItemEntity legacyItem = TaskItemEntity.builder()
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
                    .build();
            products = isTaskItemRequiresMaterials(legacyItem)
                    ? List.of(buildProductWithRecipe(task, legacyItem))
                    : List.of();
        } else {
            products = List.of();
        }

        return MaterialsTaskViewResponse.builder()
                .taskId(task.getId())
                .taskCode(task.getCode())
                .productionOrderCode(task.getProductionOrderCode())
                .productionOrderId(task.getProductionOrderId())
                .customerName(po != null ? po.getCustomerName() : null)
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
        Integer quantity = resolveTaskItemRecipeQuantity(item);

        Map<Long, TaskItemMaterialPickEntity> picksByMaterial = new HashMap<>();
        if (item.getId() != null) {
            for (TaskItemMaterialPickEntity p : taskItemMaterialPickRepository.findByTaskItemId(item.getId())) {
                picksByMaterial.put(p.getMaterialId(), p);
            }
        }

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
                            TaskItemMaterialPickEntity pick = picksByMaterial.get(bomItem.getMaterialId());

                            return MaterialsTaskViewResponse.RecipeMaterial.builder()
                                    .materialId(bomItem.getMaterialId())
                                    .materialName(material != null ? material.getName() : null)
                                    .materialSku(material != null ? material.getSku() : null)
                                    .quantityPerUnit(bomItem.getQuantity())
                                    .totalQuantity(totalQty)
                                    .availableStock(availableStock)
                                    .sufficientStock(sufficientStock)
                                    .measurementUnit(bomItem.getMeasurementUnit())
                                    .picked(pick != null && Boolean.TRUE.equals(pick.getPicked()))
                                    .pickedAt(pick != null ? pick.getPickedAt() : null)
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

    @PostMapping("/generate/{productionOrderId}/selective")
    @Transactional
    public ResponseEntity<List<TaskResponse>> generateTasksSelective(
            @PathVariable Long productionOrderId,
            @RequestBody List<Long> selectedItemIds)
            throws ResourceNotFoundException, BusinessException {

        List<TaskEntity> generatedTasks = productionTaskGenerationService
                .generateTasksForSelectedItems(productionOrderId, selectedItemIds);
        return ResponseEntity.ok(generatedTasks.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @PostMapping("/generate-cincho-materials/{productionOrderId}")
    @Transactional
    public ResponseEntity<List<TaskResponse>> generateCinchoMaterialsTasks(
            @PathVariable Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {

        List<TaskEntity> generated = productionTaskGenerationService.generateCinchoMaterialsTasks(productionOrderId);
        return ResponseEntity.ok(generated.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    private int resolveTaskItemRecipeQuantity(TaskItemEntity item) {
        if (item.getProductionOrderItemId() != null) {
            return productionOrderItemRepository.findById(item.getProductionOrderItemId())
                    .map(ProductionOrderItemQuantityHelper::effectiveQuantityForBom)
                    .orElse(item.getQuantity() != null ? item.getQuantity() : 1);
        }
        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
        return qty > 0 ? qty : 1;
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
        return ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
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


    private int findLeastLoadedDesk(Map<Integer, Double> deskLoads) {
        return deskLoads.entrySet().stream()
                .min(Comparator
                        .comparingDouble((Map.Entry<Integer, Double> e) -> e.getValue() != null ? e.getValue() : 0.0)
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(1);
    }

    private String generateTaskCode() throws BusinessException {
        return taskCodeGenerator.generateTaskCode();
    }

    private boolean isValidStatus(String status) {
        return "PENDING".equals(status) || "IN_PROGRESS".equals(status)
                || ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(status)
                || "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean canTransitionStatus(String currentStatus, String newStatus) {
        if (currentStatus == null) return "PENDING".equals(newStatus) || "IN_PROGRESS".equals(newStatus);
        if (currentStatus.equals(newStatus)) return true;
        if ("PENDING".equals(currentStatus)) {
            return "IN_PROGRESS".equals(newStatus) || "CANCELLED".equals(newStatus);
        }
        if ("IN_PROGRESS".equals(currentStatus)) {
            return "COMPLETED".equals(newStatus)
                    || ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(newStatus)
                    || "CANCELLED".equals(newStatus) || "PENDING".equals(newStatus);
        }
        if (ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(currentStatus)) {
            return false;
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

    private boolean canDeliverMaterials(TaskEntity entity) {
        if (entity == null) return false;
        return canDeliverMaterials(entity, taskItemRepository.findByTaskId(entity.getId()));
    }

    /** Variante con los items ya cargados, para no repetir la consulta dentro de una misma respuesta. */
    private boolean canDeliverMaterials(TaskEntity entity, List<TaskItemEntity> items) {
        if (entity == null || "CANCELLED".equals(entity.getStatus())) {
            return false;
        }
        if ("COMPLETED".equals(entity.getStatus())) {
            return false;
        }
        if (!taskRequiresMaterials(entity, items)) {
            return true;
        }
        return !Boolean.TRUE.equals(entity.getMaterialsDelivered());
    }

    /**
     * Tareas con {@code scheduledDate} = día, y si el día es hoy (Guatemala) también:
     * cola sin fecha o atrasadas, y tareas con mesa asignada aunque la fecha planificada sea otra
     * (siguen en piso y bodega debe verlas para materiales).
     */
    private List<TaskEntity> collectTasksScheduledForMaterialsDay(LocalDate targetDate) {
        List<TaskEntity> scheduled = taskRepository.findByScheduledDate(targetDate);
        return mergeActiveMaterialsBacklogForToday(targetDate, scheduled);
    }

    /**
     * Si {@code targetDate} es hoy (GT): suma tareas activas sin programar, atrasadas, o con mesa asignada
     * (aunque {@code scheduledDate} sea futura — ya están en mesa).
     */
    private List<TaskEntity> mergeActiveMaterialsBacklogForToday(LocalDate targetDate, List<TaskEntity> base) {
        Map<Long, TaskEntity> byId = new LinkedHashMap<>();
        for (TaskEntity t : base) {
            byId.put(t.getId(), t);
        }
        if (!targetDate.equals(LocalDate.now(GUATEMALA_ZONE))) {
            return new ArrayList<>(byId.values());
        }
        for (TaskEntity t : taskRepository.findPendingAndInProgressOrdered()) {
            if (shouldAugmentMaterialsDayWithTask(t, targetDate)) {
                byId.putIfAbsent(t.getId(), t);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Tareas activas que bodega debe ver el día de trabajo “hoy” además de las programadas para esa fecha. */
    private boolean shouldAugmentMaterialsDayWithTask(TaskEntity t, LocalDate targetDate) {
        LocalDate sd = t.getScheduledDate();
        Integer desk = t.getDesk();
        boolean hasDesk = desk != null && desk > 0;
        if (hasDesk) {
            return true;
        }
        return sd == null || sd.isBefore(targetDate);
    }

    /**
     * Pendiente de materiales: tarea/OP activas, requiere MP, y aún faltan ítems requeridos por entregar.
     * Usado por materials-view default (“Pendientes hoy”) y materials-view por OP.
     */
    private boolean isPendingMaterialsViewTask(TaskEntity entity) {
        if (entity == null || "CANCELLED".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus())) {
            return false;
        }
        if (entity.getProductionOrderId() != null) {
            ProductionOrderEntity order = productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null);
            if (order != null && ("COMPLETED".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus()))) {
                return false;
            }
        }
        return taskRequiresMaterials(entity) && !areRequiredTaskItemsDelivered(entity);
    }

    private boolean canDeliverMaterialsForTaskItem(TaskEntity entity, TaskItemEntity item) {
        if (entity == null || "CANCELLED".equals(entity.getStatus()) || "COMPLETED".equals(entity.getStatus())) {
            return false;
        }
        if (!isTaskItemRequiresMaterials(item)) {
            return true;
        }
        return !Boolean.TRUE.equals(item.getMaterialsDelivered());
    }

    private boolean hasActiveLeatherDelivery(Long productionOrderId) {
        if (productionOrderId == null) return false;
        return leatherMovementRepository.findByProductionOrderIdOrderByCreatedAtDesc(productionOrderId).stream()
                .anyMatch(m -> "SALIDA".equals(m.getMovementType()));
    }

    private String getWorkflowStatus(TaskEntity entity) {
        return getWorkflowStatus(entity, taskItemRepository.findByTaskId(entity.getId()));
    }

    /** Variante con los items ya cargados, para no repetir la consulta dentro de una misma respuesta. */
    private String getWorkflowStatus(TaskEntity entity, List<TaskItemEntity> items) {
        if ("CANCELLED".equals(entity.getStatus())) return "CANCELLED";
        if (!Boolean.TRUE.equals(entity.getLeatherDelivered())) return "PENDING_LEATHER";
        if (!Boolean.TRUE.equals(entity.getDieCutReady())) return "PENDING_DIE_CUT";
        if (!hasEnteredTable(entity)) return "PENDING_TABLE_ENTRY";
        if (taskRequiresMaterials(entity, items) && !areRequiredTaskItemsDelivered(entity, items)) {
            return "PENDING_MATERIAL_DELIVERY";
        }
        if (ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(entity.getStatus())) {
            return "PENDING_WAREHOUSE_RECEIPT";
        }
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
        return taskRequiresMaterials(entity, taskItemRepository.findByTaskId(entity.getId()));
    }

    /** Variante con los items ya cargados, para no repetir la consulta dentro de una misma respuesta. */
    private boolean taskRequiresMaterials(TaskEntity entity, List<TaskItemEntity> items) {
        if (entity == null) return true;
        if (items != null && !items.isEmpty()) {
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

    /**
     * El troquelado de la tarea es el Y-logico del de sus productos.
     *
     * <p>Mismo criterio que {@link #areTaskItemsLeatherDelivered}: una tarea esta troquelada
     * cuando TODOS sus productos lo estan. Si no tiene productos, se respeta el flag que ya
     * tuviera la tarea, porque no hay nada desde donde calcularlo.
     */
    private boolean areTaskItemsDieCut(TaskEntity entity) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(entity.getId());
        if (items.isEmpty()) {
            return Boolean.TRUE.equals(entity.getDieCutReady());
        }
        return items.stream().allMatch(item -> Boolean.TRUE.equals(item.getDieCutReady()));
    }

    /**
     * Si este producto tiene cuero para poder troquelarse.
     *
     * <p>Acepta el cuero del producto O el de la tarea. La entrega de cuero se registra por
     * orden y marca la tarea sin bajar a los productos, asi que mirar solo el producto
     * dejaria fuera a casi todo. Es el mismo criterio con el que el DTO lo pinta.
     */
    private static boolean hasLeatherForItem(TaskEntity task, TaskItemEntity item) {
        return Boolean.TRUE.equals(item.getLeatherDelivered())
                || Boolean.TRUE.equals(task.getLeatherDelivered());
    }

    /** Nombre legible del producto, para que el error diga cual es y no solo que fallo. */
    private static String descripcionItem(TaskItemEntity item) {
        String code = item.getProductCode() == null ? "" : item.getProductCode().trim();
        String name = item.getProductName() == null ? "" : item.getProductName().trim();
        if (!code.isEmpty() && !name.isEmpty()) return code + " - " + name;
        if (!code.isEmpty()) return code;
        if (!name.isEmpty()) return name;
        return "el producto";
    }

    private boolean areRequiredTaskItemsDelivered(TaskEntity entity) {
        return areRequiredTaskItemsDelivered(entity, taskItemRepository.findByTaskId(entity.getId()));
    }

    /** Variante con los items ya cargados, para no repetir la consulta dentro de una misma respuesta. */
    private boolean areRequiredTaskItemsDelivered(TaskEntity entity, List<TaskItemEntity> items) {
        if (items == null || items.isEmpty()) {
            return !taskRequiresMaterials(entity, items) || Boolean.TRUE.equals(entity.getMaterialsDelivered());
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
            recalculateTaskTotals(sourceTask, sourceItems);
            sourceTask.setMaterialsDelivered(areRequiredTaskItemsDelivered(sourceTask));
            sourceTask.setMaterialsDeliveredAt(Boolean.TRUE.equals(sourceTask.getMaterialsDelivered()) ? LocalDateTime.now() : null);
            return;
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

    private String resolveDeskSupervisorName(Integer desk, LocalDate scheduledDate) {
        if (desk == null) {
            return null;
        }
        LocalDate asOf = scheduledDate != null ? scheduledDate : LocalDate.now(GUATEMALA_ZONE);
        String name = productionDeskSupervisorRepository
                .findTopByDeskAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(desk, asOf)
                .map(ProductionDeskSupervisorEntity::getSupervisorName)
                .map(String::trim)
                .orElse("");
        return name.isEmpty() ? null : name;
    }

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

        // Al completar una tarea se limpia `desk` y el numero queda en `worked_desk`.
        // Sin este respaldo, la boleta de una tarea terminada saldria sin mesa ni
        // encargado, que es justo lo que hace util reimprimirla.
        Integer deskBoleta = task.getDesk() != null ? task.getDesk() : task.getWorkedDesk();
        String deskSupervisorName = resolveDeskSupervisorName(deskBoleta, task.getScheduledDate());

        return TaskTicketResponse.builder()
                .taskId(task.getId())
                .taskCode(task.getCode())
                .desk(deskBoleta)
                .deskSupervisorName(deskSupervisorName)
                .scheduledDate(task.getScheduledDate())
                .startTime(task.getStartTime())
                .startedAt(task.getStartedAt())
                .estimatedHours(task.getEstimatedHours())
                .status(task.getStatus())
                .completedAt(task.getCompletedAt())
                .dieCutReady(task.getDieCutReady())
                .productionOrderCode(task.getProductionOrderCode())
                .deliveryDate(task.getDeliveryDate())
                .orderObservations(stripInternalOrderTags(po != null ? po.getObservations() : null))
                .items(ticketItems)
                .build();
    }

    /**
     * Marcadores internos que el módulo de OPV guarda dentro de las observaciones de la orden:
     * {@code __OPV_PACKING__} (JSON de empaque) y {@code __OPV_SHIPPING__} (costo de envío).
     * No son notas para la mesa, así que se retiran antes de llevarlas a la boleta — el mismo
     * criterio que aplica {@code ProductionOrderController.parseOrderMeta} en la pantalla de la OP.
     */
    private static String stripInternalOrderTags(String observations) {
        if (observations == null || observations.isBlank()) {
            return observations;
        }
        String cleaned = observations.lines()
                .filter(line -> {
                    String trimmed = line.trim();
                    return !trimmed.startsWith("__OPV_PACKING__:")
                            && !trimmed.startsWith("__OPV_SHIPPING__:");
                })
                .collect(Collectors.joining("\n"))
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    // ==================== MAPPING ====================

    private TaskResponse toResponse(TaskEntity entity) {
        return toResponse(entity, taskItemRepository.findByTaskId(entity.getId()));
    }

    /**
     * Variante con los items ya cargados, para que el listado completo pueda traerlos
     * todos en una sola consulta en vez de una por tarea.
     */
    private TaskResponse toResponse(TaskEntity entity, List<TaskItemEntity> itemEntities) {
        List<TaskResponse.TaskItemDTO> itemDTOs = itemEntities.stream()
                .map(item -> TaskResponse.TaskItemDTO.builder()
                        .id(item.getId())
                        .productionOrderItemId(item.getProductionOrderItemId())
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
                        .dieCutReady(Boolean.TRUE.equals(item.getDieCutReady()))
                        .dieCutDate(item.getDieCutDate())
                        .dieCutPlannedDate(item.getDieCutPlannedDate())
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
                .workedDesk(entity.getWorkedDesk())
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
                .materialsDelivered(areRequiredTaskItemsDelivered(entity, itemEntities))
                .materialsDeliveredAt(entity.getMaterialsDeliveredAt())
                .requiresMaterials(taskRequiresMaterials(entity, itemEntities))
                .workflowStatus(getWorkflowStatus(entity, itemEntities))
                .canDeliverMaterials(canDeliverMaterials(entity, itemEntities))
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .items(itemDTOs)
                .build();
    }

    // ==================== INNER CLASSES ====================

    /**
     * OPV / OPK / OPI / OPCK / OPL según tipo o prefijo de código; null si no aplica al tablero de prioridad.
     */
    private static String distributionFamilyLabel(String orderType, String code) {
        return ProductionPlanningConstants.distributionFamilyLabel(orderType, code);
    }

    private static boolean canOvercapDeskDay(String orderType) {
        return ProductionPlanningConstants.canOvercapDeskDay(orderType);
    }

    /** Primera prioridad que el usuario puede asignar a mano: 0 y 1 son cupos reservados. */
    private static final int MIN_MANUAL_SCHEDULING_PRIORITY = 2;
    /** Última: 100 es el valor por defecto de las órdenes sin prioridad propia. */
    private static final int MAX_MANUAL_SCHEDULING_PRIORITY = 99;

    private void mergeSchedulingPrioritiesFromRequest(Map<String, Integer> schedulingPriorities) {
        if (schedulingPriorities == null || schedulingPriorities.isEmpty()) {
            return;
        }
        Map<Long, Integer> porId = new HashMap<>();
        for (Map.Entry<String, Integer> e : schedulingPriorities.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            long id;
            try {
                id = Long.parseLong(e.getKey().trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            Integer p = e.getValue();
            // Rango válido 2..99. Por debajo están los cupos reservados (0 = venta en
            // línea, 1 = cliente kiosko) y 100 es el valor por defecto del resto: una
            // prioridad manual fuera de ese rango no adelanta a nadie o empata con una
            // familia, y en ambos casos el usuario no vería reflejado lo que arrastró.
            if (p == null || p < MIN_MANUAL_SCHEDULING_PRIORITY || p > MAX_MANUAL_SCHEDULING_PRIORITY) {
                continue;
            }
            porId.put(id, p);
        }

        if (porId.isEmpty()) return;

        // Antes era una consulta y un save por orden. Con una cola de 30 órdenes eso eran
        // 60 viajes a la base dentro de la transacción que sostiene el candado.
        List<ProductionOrderEntity> ordenes = productionOrderRepository.findAllById(porId.keySet());
        for (ProductionOrderEntity po : ordenes) {
            po.setSchedulingPriority(porId.get(po.getId()));
        }
        productionOrderRepository.saveAll(ordenes);
    }

    private Map<Long, Integer> loadSchedulingPriorityByProductionOrderId(Set<Long> productionOrderIds) {
        if (productionOrderIds == null || productionOrderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> out = new HashMap<>();
        for (ProductionOrderEntity po : productionOrderRepository.findAllById(productionOrderIds)) {
            out.put(po.getId(), Optional.ofNullable(po.getSchedulingPriority()).orElse(Integer.MAX_VALUE));
        }
        for (Long id : productionOrderIds) {
            out.putIfAbsent(id, Integer.MAX_VALUE);
        }
        return out;
    }

    private record DeskDateKey(LocalDate date, Integer desk) {}
}

