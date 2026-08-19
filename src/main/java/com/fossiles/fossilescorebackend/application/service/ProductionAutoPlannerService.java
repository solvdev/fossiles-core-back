package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionAutoPlanResult;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionDaySalesSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
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
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionAutoPlannerService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskRepository taskRepository;
    private final TaskOrganizerService taskOrganizerService;
    private final LeatherRequirementService leatherRequirementService;
    private final ProductionDeskCountService productionDeskCountService;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ReentrantLock planLock = new ReentrantLock();

    public void planQuietly(Long productionOrderId) {
        if (productionOrderId == null) {
            return;
        }
        try {
            planOrder(productionOrderId);
        } catch (Exception e) {
            log.warn("Auto-plan OP {}: {}", productionOrderId, e.getMessage());
        }
    }

    public void planAllQuietly() {
        try {
            planPending();
        } catch (Exception e) {
            log.warn("Auto-plan global: {}", e.getMessage());
        }
    }

    @Transactional
    public ProductionAutoPlanResult planPending() throws BusinessException, ResourceNotFoundException {
        planLock.lock();
        try {
            return planOrders(eligibleOrders(null));
        } finally {
            planLock.unlock();
        }
    }

    @Transactional
    public ProductionAutoPlanResult planOrder(Long productionOrderId)
            throws BusinessException, ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        planLock.lock();
        try {
            return planOrders(List.of(po));
        } finally {
            planLock.unlock();
        }
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

    private ProductionAutoPlanResult planOrders(List<ProductionOrderEntity> orders)
            throws BusinessException, ResourceNotFoundException {
        ProductionAutoPlanResult result = ProductionAutoPlanResult.builder().build();
        if (orders == null || orders.isEmpty()) {
            return result;
        }

        LocalDate today = DeskSlotFinder.nextWorkday(GuatemalaDateTime.today());
        int numDesks = productionDeskCountService.getDay(today).getNumDesks();
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

            for (ProductionOrderItemEntity item : items) {
                ProductEntity product = item.getProductId() != null
                        ? productRepository.findById(item.getProductId()).orElse(null)
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
                int units = TaskQuantityChunker.resolveUnitsPerTask(product.getUnitsPerTask());
                List<Integer> chunks = TaskQuantityChunker.splitQuantity(remaining, units);

                if (cincho) {
                    for (int qty : chunks) {
                        TaskEntity created = taskOrganizerService.createAutoCinchoTask(
                                CreateManualTaskRequest.builder()
                                        .productionOrderId(po.getId())
                                        .scheduledDate(today)
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

                double prd = product.getPrdTime() != null && product.getPrdTime() > 0
                        ? product.getPrdTime()
                        : ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;

                int leftover = 0;
                String leftoverReason = null;
                for (int qty : chunks) {
                    LeatherRequirementService.LeatherNeed need =
                            leatherRequirementService.resolveNeed(product, item.getColorId(), qty);
                    if (need.blocked() || !leatherRequirementService.canCover(need, reserved)) {
                        leftover += qty;
                        leftoverReason = leatherRequirementService.shortageMessage(need, reserved);
                        continue;
                    }
                    double baseHours = online ? 0.0 : roundHours(qty * prd);
                    DeskSlotFinder.Slot slot = DeskSlotFinder.findEarliest(schedule, numDesks, today, baseHours);
                    TaskEntity created = taskOrganizerService.createAutoCentroTask(
                            CreateManualTaskRequest.builder()
                                    .productionOrderId(po.getId())
                                    .desk(slot.desk())
                                    .scheduledDate(slot.date())
                                    .observations("Auto-plan")
                                    .items(List.of(CreateManualTaskRequest.ManualTaskItemRequest.builder()
                                            .productionOrderItemId(item.getId())
                                            .quantity(qty)
                                            .daySaleExtra(online)
                                            .build()))
                                    .build());
                    DeskSlotFinder.addLoad(schedule, slot, baseHours);
                    if (!need.noneRequired()) {
                        reserved.merge(need.materialId(), need.qtyFt2(), BigDecimal::add);
                    }
                    result.setCentroTasksCreated(result.getCentroTasksCreated() + 1);
                    result.getCreatedTaskIds().add(created.getId());
                    materialRequestOrders.add(po.getId());
                }
                if (leftover > 0) {
                    result.getBlockedNoLeather().add(ProductionAutoPlanResult.BlockedLeatherLine.builder()
                            .productionOrderId(po.getId())
                            .productionOrderCode(po.getCode())
                            .productionOrderItemId(item.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .remainingQuantity(leftover)
                            .reason(leftoverReason)
                            .build());
                }
            }
        }

        for (Long poId : materialRequestOrders) {
            requestMaterials(poId);
        }
        return result;
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
            double hours = ProductionPlanningConstants.isOnlineSaleOrder(null, task.getProductionOrderCode())
                    ? 0.0
                    : (task.getEstimatedHours() != null ? task.getEstimatedHours() : 0.0);
            schedule.computeIfAbsent(task.getScheduledDate(), d -> new HashMap<>())
                    .merge(task.getDesk(), hours, Double::sum);
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
