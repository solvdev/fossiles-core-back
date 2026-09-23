package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionAutoPlanResult;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionDaySalesSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
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
import com.fossiles.fossilescorebackend.infrastructure.util.TaskQuantityChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionAutoPlannerService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskRepository taskRepository;
    private final TaskOrganizerService taskOrganizerService;
    private final LeatherRequirementService leatherRequirementService;
    private final ProductionDeskCountService productionDeskCountService;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ProductionPlanningLock productionPlanningLock;
    private final TaskDeskHoursService taskDeskHoursService;

    /**
     * Este mismo bean, pero visto a través del proxy de Spring.
     *
     * <p>Llamar a {@code planPending()} con {@code this} se salta el proxy y con él
     * la anotación {@code @Transactional}: el auto-plan del cron llevaba corriendo
     * sin transacción propia, cada tarea confirmándose por su cuenta. Sin
     * transacción no hay a qué amarrar el candado de {@link ProductionPlanningLock},
     * así que el turno hay que pedirlo desde dentro de una.
     *
     * <p>{@code ObjectProvider} resuelve el bean al usarlo, no al construirlo, que es
     * lo que evita la dependencia circular de inyectarse a sí mismo.
     */
    private final ObjectProvider<ProductionAutoPlannerService> selfProvider;
    private final ProductionTaskLifecycleService productionTaskLifecycleService;

    private ProductionAutoPlannerService self() {
        return selfProvider.getObject();
    }

    /**
     * Desactivado: el plan solo corre por botón explícito en Centro
     * ({@code POST /tasks/auto-plan}). Los callers históricos siguen seguros.
     */
    public void planQuietly(Long productionOrderId) {
        // no-op
    }

    /** Desactivado: ver {@link #planQuietly(Long)}. */
    public void planAllQuietly() {
        // no-op
    }

    @Transactional
    public ProductionAutoPlanResult planPending() throws BusinessException, ResourceNotFoundException {
        return planPending(null);
    }

    @Transactional
    public ProductionAutoPlanResult planPending(LocalDate planDate)
            throws BusinessException, ResourceNotFoundException {
        return planOrders(eligibleOrders(null), planDate);
    }

    @Transactional
    public ProductionAutoPlanResult planOrder(Long productionOrderId)
            throws BusinessException, ResourceNotFoundException {
        return planOrder(productionOrderId, null);
    }

    @Transactional
    public ProductionAutoPlanResult planOrder(Long productionOrderId, LocalDate planDate)
            throws BusinessException, ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        return planOrders(List.of(po), planDate);
    }

    /**
     * Borra tareas PENDING de auto-plan (sin arrancar) del día elegido y vuelve a planificar
     * desde esa fecha para reagrupar productos bajo el cupo de horas.
     */
    @Transactional
    public ProductionAutoPlanResult regenerate(Long productionOrderId)
            throws BusinessException, ResourceNotFoundException {
        return regenerate(productionOrderId, null);
    }

    @Transactional
    public ProductionAutoPlanResult regenerate(Long productionOrderId, LocalDate planDate)
            throws BusinessException, ResourceNotFoundException {
        LocalDate from = resolvePlanStart(planDate);
        int cleared = clearPendingAutoPlanTasks(productionOrderId, from);
        ProductionAutoPlanResult result = productionOrderId != null
                ? planOrder(productionOrderId, from)
                : planPending(from);
        result.setClearedAutoPlanTasks(cleared);
        result.setPlanDate(from);
        if (cleared > 0) {
            result.getNotes().add("Se liberaron " + cleared
                    + " tarea(s) auto-plan pendientes del " + from + " para reagrupar productos.");
        }
        return result;
    }

    private static LocalDate resolvePlanStart(LocalDate planDate) {
        LocalDate base = planDate != null ? planDate : GuatemalaDateTime.today();
        return DeskSlotFinder.nextWorkday(base);
    }

    private int clearPendingAutoPlanTasks(Long productionOrderId, LocalDate planDate) {
        List<TaskEntity> candidates = taskRepository.findByStatus("PENDING").stream()
                .filter(t -> t.getStartedAt() == null && t.getCompletedAt() == null)
                .filter(t -> isAutoPlanObservation(t.getObservations()))
                .filter(t -> productionOrderId == null
                        || Objects.equals(productionOrderId, t.getProductionOrderId()))
                .filter(t -> planDate == null
                        || planDate.equals(t.getScheduledDate())
                        || t.getScheduledDate() == null)
                .toList();
        Set<Long> orderIds = new HashSet<>();
        for (TaskEntity task : candidates) {
            if (task.getId() == null) {
                continue;
            }
            taskItemRepository.deleteByTaskId(task.getId());
            taskRepository.deleteById(task.getId());
            if (task.getProductionOrderId() != null) {
                orderIds.add(task.getProductionOrderId());
            }
        }
        for (Long poId : orderIds) {
            productionTaskLifecycleService.syncProductionOrderStatusFromTasks(poId);
        }
        return candidates.size();
    }

    private static boolean isAutoPlanObservation(String observations) {
        if (observations == null || observations.isBlank()) {
            return false;
        }
        String o = observations.trim();
        return o.equals("Auto-plan") || o.equals("Auto-plan cinchos") || o.startsWith("Auto-plan");
    }

    @Transactional(readOnly = true)
    public List<ProductionAutoPlanResult.BlockedLeatherLine> listBlockedLeather() {
        try {
            return collectBlocked(eligibleOrders(null));
        } catch (Exception e) {
            log.warn("Cola sin cuero: {}", e.getMessage());
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public ProductionDaySalesSummaryResponse daySalesSummary(LocalDate date) {
        LocalDate day = date != null ? date : GuatemalaDateTime.today();
        ProductionDaySalesSummaryResponse out = ProductionDaySalesSummaryResponse.builder().date(day).build();
        Map<Long, BigDecimal> reserved = new HashMap<>(leatherRequirementService.committedFt2ByMaterial());

        List<ProductionOrderEntity> orders = productionOrderRepository.findActiveOrders().stream()
                .filter(po -> po.getCreatedAt() != null && po.getCreatedAt().toLocalDate().equals(day))
                .filter(this::isDaySalesOrder)
                .sorted(ProductionOrderPlanPriority.comparator())
                .toList();

        for (ProductionOrderEntity po : orders) {
            LineTotals totals = summarizeOrder(po, reserved);
            ProductionDaySalesSummaryResponse.Row row = ProductionDaySalesSummaryResponse.Row.builder()
                    .productionOrderId(po.getId())
                    .code(po.getCode())
                    .orderType(po.getOrderType())
                    .customerName(po.getCustomerName())
                    .onlineSale(ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode()))
                    .status(po.getStatus())
                    .remainingCentroQty(totals.remainingCentro)
                    .remainingCinchoQty(totals.remainingCincho)
                    .reason(totals.reason)
                    .build();
            if (totals.goesToCentro) {
                out.getGoingToProduction().add(row);
            } else {
                out.getNotGoingToProduction().add(row);
            }
        }
        return out;
    }

    private ProductionAutoPlanResult planOrders(List<ProductionOrderEntity> orders, LocalDate planDate)
            throws BusinessException, ResourceNotFoundException {
        ProductionAutoPlanResult result = ProductionAutoPlanResult.builder().build();
        if (orders == null || orders.isEmpty()) {
            return result;
        }
        // Turno antes de leer nada: de aquí al commit, la foto de la carga de las
        // mesas y las tareas que se creen a partir de ella son de este hilo solo.
        productionPlanningLock.acquire();

        LocalDate startDay = resolvePlanStart(planDate);
        result.setPlanDate(startDay);
        int numDesks = productionDeskCountService.getDay(startDay).getNumDesks();
        Map<LocalDate, Map<Integer, Double>> schedule = loadSchedule();
        Map<Long, BigDecimal> reserved = new HashMap<>(leatherRequirementService.committedFt2ByMaterial());
        Set<Long> materialRequestOrders = new HashSet<>();

        List<ProductionOrderEntity> sorted = orders.stream()
                .filter(this::isEligible)
                .sorted(ProductionOrderPlanPriority.comparator())
                .toList();

        for (ProductionOrderEntity po : sorted) {
            List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId())
                    .stream()
                    .sorted(Comparator.comparing(ProductionOrderItemEntity::getId))
                    .toList();
            if (items.isEmpty()) {
                continue;
            }
            List<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).toList();
            Map<Long, Integer> assigned = taskItemRepository.assignedQuantityMap(itemIds);
            boolean online = ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode());
            Map<Long, ProductEntity> productsById = loadProducts(items);
            // Deja colores en la sesión para que cada tarea no los vuelva a pedir.
            colorRepository.findAllById(items.stream()
                    .map(ProductionOrderItemEntity::getColorId)
                    .filter(id -> id != null && id > 0)
                    .collect(java.util.stream.Collectors.toSet()));

            // Cinchos: una tarea por chunk (mesa cinchos, flujo aparte).
            // Centro: juntar chunks de varios productos en una misma tarea hasta 4 h.
            List<CentroPackChunk> centroChunks = new ArrayList<>();
            Map<Long, Integer> leatherLeftoverQty = new HashMap<>();
            Map<Long, String> leatherLeftoverReason = new HashMap<>();
            Map<Long, ProductEntity> leftoverProductByItemId = new HashMap<>();

            for (ProductionOrderItemEntity item : items) {
                ProductEntity product = item.getProductId() != null
                        ? productsById.get(item.getProductId())
                        : null;
                if (product == null || ProductCinchoType.isPackagingProductCode(product.getCode())) {
                    continue;
                }
                int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
                int remaining = Math.max(0, total - assigned.getOrDefault(item.getId(), 0));
                if (remaining <= 0) {
                    continue;
                }
                boolean cincho = CinchoProductUtils.isCinchoLineForProduction(product);
                double prd = product.getPrdTime() != null && product.getPrdTime() > 0
                        ? product.getPrdTime()
                        : ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
                int unitsConfigured = TaskQuantityChunker.resolveUnitsPerTask(product.getUnitsPerTask());
                // No generar chunks mayores al cupo de mesa (salvo 1 ud si prd > 4 h).
                int maxByHours = Math.max(1, (int) Math.floor(
                        ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY / prd));
                int units = Math.min(unitsConfigured, maxByHours);
                List<Integer> chunks = TaskQuantityChunker.splitQuantity(remaining, units);

                if (cincho) {
                    for (int qty : chunks) {
                        TaskEntity created = taskOrganizerService.createAutoCinchoTask(
                                CreateManualTaskRequest.builder()
                                        .productionOrderId(po.getId())
                                        .scheduledDate(startDay)
                                        .observations("Auto-plan cinchos")
                                        .items(List.of(CreateManualTaskRequest.ManualTaskItemRequest.builder()
                                                .productionOrderItemId(item.getId())
                                                .quantity(qty)
                                                .daySaleExtra(online)
                                                .build()))
                                        .build());
                        result.setCinchoTasksCreated(result.getCinchoTasksCreated() + 1);
                        result.getCreatedTaskIds().add(created.getId());
                        materialRequestOrders.add(po.getId());
                    }
                    continue;
                }

                for (int qty : chunks) {
                    LeatherRequirementService.LeatherNeed need =
                            leatherRequirementService.resolveNeed(product, item.getColorId(), qty);
                    if (need.blocked() || !leatherRequirementService.canCover(need, reserved)) {
                        leatherLeftoverQty.merge(item.getId(), qty, Integer::sum);
                        leatherLeftoverReason.putIfAbsent(item.getId(),
                                leatherRequirementService.shortageMessage(need, reserved));
                        leftoverProductByItemId.put(item.getId(), product);
                        continue;
                    }
                    // Reserva tentativa ya: al empaquetar varios chunks no se debe
                    // revalidar contra el mismo cupo libre.
                    if (!need.noneRequired()) {
                        reserved.merge(need.materialId(), need.qtyFt2(), BigDecimal::add);
                    }
                    double hours = online ? 0.0 : roundHours(qty * prd);
                    centroChunks.add(new CentroPackChunk(item, product, qty, hours, need, online));
                }
            }

            for (List<CentroPackChunk> group : packCentroChunks(centroChunks)) {
                double groupHours = group.stream().mapToDouble(CentroPackChunk::hours).sum();
                DeskSlotFinder.Slot slot = DeskSlotFinder.findEarliest(
                        schedule, numDesks, startDay, online ? 0.0 : groupHours);

                Map<Long, CreateManualTaskRequest.ManualTaskItemRequest> linesByItemId = new HashMap<>();
                for (CentroPackChunk chunk : group) {
                    linesByItemId.merge(
                            chunk.item().getId(),
                            CreateManualTaskRequest.ManualTaskItemRequest.builder()
                                    .productionOrderItemId(chunk.item().getId())
                                    .quantity(chunk.qty())
                                    .daySaleExtra(chunk.online())
                                    .build(),
                            (a, b) -> CreateManualTaskRequest.ManualTaskItemRequest.builder()
                                    .productionOrderItemId(a.getProductionOrderItemId())
                                    .quantity(a.getQuantity() + b.getQuantity())
                                    .daySaleExtra(Boolean.TRUE.equals(a.getDaySaleExtra())
                                            || Boolean.TRUE.equals(b.getDaySaleExtra()))
                                    .build());
                }

                TaskEntity created = taskOrganizerService.createAutoCentroTask(
                        CreateManualTaskRequest.builder()
                                .productionOrderId(po.getId())
                                .desk(slot.desk())
                                .scheduledDate(slot.date())
                                .observations("Auto-plan")
                                .items(new ArrayList<>(linesByItemId.values()))
                                .build());
                DeskSlotFinder.addLoad(schedule, slot, online ? 0.0 : groupHours);
                result.setCentroTasksCreated(result.getCentroTasksCreated() + 1);
                result.getCreatedTaskIds().add(created.getId());
                materialRequestOrders.add(po.getId());
            }

            for (Map.Entry<Long, Integer> entry : leatherLeftoverQty.entrySet()) {
                Long itemId = entry.getKey();
                ProductEntity product = leftoverProductByItemId.get(itemId);
                result.getBlockedNoLeather().add(ProductionAutoPlanResult.BlockedLeatherLine.builder()
                        .productionOrderId(po.getId())
                        .productionOrderCode(po.getCode())
                        .productionOrderItemId(itemId)
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .remainingQuantity(entry.getValue())
                        .reason(leatherLeftoverReason.get(itemId))
                        .build());
            }
        }

        for (Long poId : materialRequestOrders) {
            requestMaterials(poId);
        }
        return result;
    }

    /**
     * Empaqueta chunks de centro en grupos ≤ {@link ProductionPlanningConstants#MAX_HOURS_PER_DESK_PER_DAY},
     * permitiendo varios productos en la misma tarea (misma lógica que generación clásica).
     */
    private static List<List<CentroPackChunk>> packCentroChunks(List<CentroPackChunk> chunks) {
        List<List<CentroPackChunk>> groups = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return groups;
        }
        List<CentroPackChunk> current = new ArrayList<>();
        double currentHours = 0;
        for (CentroPackChunk chunk : chunks) {
            if (!current.isEmpty()
                    && currentHours + chunk.hours()
                    > ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY + 1e-9) {
                groups.add(current);
                current = new ArrayList<>();
                currentHours = 0;
            }
            current.add(chunk);
            currentHours += chunk.hours();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private record CentroPackChunk(
            ProductionOrderItemEntity item,
            ProductEntity product,
            int qty,
            double hours,
            LeatherRequirementService.LeatherNeed need,
            boolean online) {
    }

    private Map<Long, ProductEntity> loadProducts(List<ProductionOrderItemEntity> items) {
        Set<Long> productIds = items.stream()
                .map(ProductionOrderItemEntity::getProductId)
                .filter(id -> id != null && id > 0)
                .collect(java.util.stream.Collectors.toSet());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(productIds).stream()
                .collect(java.util.stream.Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
    }

    private List<ProductionAutoPlanResult.BlockedLeatherLine> collectBlocked(List<ProductionOrderEntity> orders) {
        List<ProductionAutoPlanResult.BlockedLeatherLine> blocked = new ArrayList<>();
        Map<Long, BigDecimal> reserved = new HashMap<>(leatherRequirementService.committedFt2ByMaterial());
        for (ProductionOrderEntity po : orders) {
            if (!isEligible(po)) {
                continue;
            }
            List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId())
                    .stream()
                    .sorted(Comparator.comparing(ProductionOrderItemEntity::getId))
                    .toList();
            List<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).toList();
            Map<Long, Integer> assigned = taskItemRepository.assignedQuantityMap(itemIds);
            for (ProductionOrderItemEntity item : items) {
                ProductEntity product = item.getProductId() != null
                        ? productRepository.findById(item.getProductId()).orElse(null)
                        : null;
                if (product == null || ProductCinchoType.isPackagingProductCode(product.getCode())) {
                    continue;
                }
                if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                    continue;
                }
                int remaining = Math.max(0, ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item)
                        - assigned.getOrDefault(item.getId(), 0));
                if (remaining <= 0) {
                    continue;
                }
                LeatherRequirementService.LeatherNeed need =
                        leatherRequirementService.resolveNeed(product, item.getColorId(), remaining);
                if (need.blocked() || !leatherRequirementService.canCover(need, reserved)) {
                    blocked.add(ProductionAutoPlanResult.BlockedLeatherLine.builder()
                            .productionOrderId(po.getId())
                            .productionOrderCode(po.getCode())
                            .productionOrderItemId(item.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .remainingQuantity(remaining)
                            .reason(leatherRequirementService.shortageMessage(need, reserved))
                            .build());
                } else if (!need.noneRequired()) {
                    reserved.merge(need.materialId(), need.qtyFt2(), BigDecimal::add);
                }
            }
        }
        return blocked;
    }

    private LineTotals summarizeOrder(ProductionOrderEntity po, Map<Long, BigDecimal> reserved) {
        LineTotals totals = new LineTotals();
        if ("DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim())) {
            totals.reason = "Borrador: espera autorización de producción";
            return totals;
        }
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId());
        List<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).toList();
        Map<Long, Integer> assigned = itemIds.isEmpty() ? Map.of() : taskItemRepository.assignedQuantityMap(itemIds);
        boolean leatherBlocked = false;
        String leatherReason = null;
        int assignedCentro = 0;
        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            if (product == null || ProductCinchoType.isPackagingProductCode(product.getCode())) {
                continue;
            }
            int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            int done = assigned.getOrDefault(item.getId(), 0);
            assignedCentro += CinchoProductUtils.isCinchoLineForProduction(product) ? 0 : done;
            int remaining = Math.max(0, total - done);
            if (remaining <= 0) {
                continue;
            }
            if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                totals.remainingCincho += remaining;
                continue;
            }
            totals.remainingCentro += remaining;
            LeatherRequirementService.LeatherNeed need =
                    leatherRequirementService.resolveNeed(product, item.getColorId(), remaining);
            if (need.blocked() || !leatherRequirementService.canCover(need, reserved)) {
                leatherBlocked = true;
                leatherReason = leatherRequirementService.shortageMessage(need, reserved);
            } else if (!need.noneRequired()) {
                reserved.merge(need.materialId(), need.qtyFt2(), BigDecimal::add);
            }
        }
        if (totals.remainingCentro > 0 && leatherBlocked) {
            totals.reason = leatherReason;
            return totals;
        }
        if (totals.remainingCentro > 0) {
            totals.goesToCentro = true;
            totals.reason = "Pendiente de fabricar en centro";
            return totals;
        }
        if (assignedCentro > 0) {
            totals.goesToCentro = true;
            totals.reason = "Ya cubierto en tareas de centro";
            return totals;
        }
        if (totals.remainingCincho > 0) {
            totals.reason = "Solo cinchos: van a mesa cinchos, no al centro";
            return totals;
        }
        totals.reason = "Sin pendiente de fabricación (bodega/devoluciones o ya cubierto)";
        return totals;
    }

    private Map<LocalDate, Map<Integer, Double>> loadSchedule() {
        Map<LocalDate, Map<Integer, Double>> schedule = new HashMap<>();
        for (TaskEntity task : taskRepository.findPendingAndInProgressOrdered()) {
            if (task.getScheduledDate() == null || task.getDesk() == null) {
                continue;
            }
            // Antes calculaba las horas aquí con estimatedHours crudo, sin descontar los
            // ítems de venta del día: la misma mesa se veía con un número aquí y con otro
            // en plan-window. Ahora los dos leen del mismo sitio.
            schedule.computeIfAbsent(task.getScheduledDate(), d -> new HashMap<>())
                    .merge(task.getDesk(), taskDeskHoursService.baseHours(task), Double::sum);
        }
        return schedule;
    }

    private void requestMaterials(Long productionOrderId) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        for (ProductionOrderItemEntity item : items) {
            if (item.getProductId() == null) {
                continue;
            }
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            if (qty <= 0) {
                continue;
            }
            try {
                smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                        productionOrderId, item.getProductId(), BigDecimal.valueOf(qty));
            } catch (Exception e) {
                log.warn("Solicitud de materiales OP {} producto {}: {}",
                        productionOrderId, item.getProductId(), e.getMessage());
            }
        }
    }

    private List<ProductionOrderEntity> eligibleOrders(Long onlyId) {
        return productionOrderRepository.findActiveOrders().stream()
                .filter(this::isEligible)
                .filter(po -> onlyId == null || onlyId.equals(po.getId()))
                .toList();
    }

    private boolean isEligible(ProductionOrderEntity po) {
        if (po == null) {
            return false;
        }
        String st = String.valueOf(po.getStatus()).trim().toUpperCase(Locale.ROOT);
        return !"DRAFT".equals(st) && !"CANCELLED".equals(st) && !"COMPLETED".equals(st);
    }

    private boolean isDaySalesOrder(ProductionOrderEntity po) {
        String t = po.getOrderType() == null ? "" : po.getOrderType().trim().toUpperCase(Locale.ROOT);
        return "VENTA_EN_LINEA".equals(t)
                || "CLIENTE_KIOSKO".equals(t)
                || "NORMAL".equals(t)
                || "MARCAS".equals(t)
                || "OPV".equals(t)
                || ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode());
    }

    private static double roundHours(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class LineTotals {
        int remainingCentro;
        int remainingCincho;
        boolean goesToCentro;
        String reason;
    }
}
