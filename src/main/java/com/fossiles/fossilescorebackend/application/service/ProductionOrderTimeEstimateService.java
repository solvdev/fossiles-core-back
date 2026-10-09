package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderTimeEstimateResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estima días hábiles de una OP: Σ(prd_time × qty), ajustado por eficiencia del
 * dashboard (desde {@link ProductionPlanningConstants#KPI_EFFICIENCY_FROM}) y
 * capacidad (lun–jue 9 h, vie 8 h, sáb/dom 0).
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderTimeEstimateService {

    private static final double HOURS_MON_THU = 9.0;
    private static final double HOURS_FRIDAY = 8.0;
    private static final int MAX_DAYS = 400;

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final TaskRepository taskRepository;
    private final ProductionDeskCountService productionDeskCountService;

    @Transactional(readOnly = true)
    public ProductionOrderTimeEstimateResponse estimate(Long productionOrderId, LocalDate efficiencyFrom)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        LocalDate from = efficiencyFrom != null
                ? efficiencyFrom
                : ProductionPlanningConstants.KPI_EFFICIENCY_FROM;
        LocalDate start = GuatemalaDateTime.today();
        int deskCount = Math.max(1, productionDeskCountService.getDay(start).getNumDesks());

        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(order.getId());
        Set<Long> productIds = items.stream()
                .map(ProductionOrderItemEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> productsById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));

        List<ProductionOrderTimeEstimateResponse.LineEstimate> lines = new ArrayList<>();
        double theoreticalHours = 0.0;
        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            double prd = resolvePrdTime(product);
            double lineHours = round2(qty * prd);
            theoreticalHours += lineHours;
            lines.add(ProductionOrderTimeEstimateResponse.LineEstimate.builder()
                    .itemId(item.getId())
                    .productId(item.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .quantity(qty)
                    .prdTimePerUnit(prd)
                    .lineHours(lineHours)
                    .build());
        }
        theoreticalHours = round2(theoreticalHours);

        // Misma base que "Eficiencia mesas" del dashboard (mesas 1..N con tiempo real).
        EfficiencyStats efficiency = computeEfficiency(from);
        double adjustedHours = theoreticalHours;
        if (efficiency.percent() != null && efficiency.percent() > 0) {
            adjustedHours = round2(theoreticalHours * (100.0 / efficiency.percent()));
        }

        LocalDate endDate = projectEndDate(start, adjustedHours, deskCount);
        int businessDays = countBusinessDays(start, endDate);

        return ProductionOrderTimeEstimateResponse.builder()
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .orderType(order.getOrderType())
                .status(order.getStatus())
                .deskCount(deskCount)
                .efficiencyFrom(from)
                .efficiencyPercent(efficiency.percent())
                .measuredTasks(efficiency.measuredTasks())
                .theoreticalHours(theoreticalHours)
                .adjustedHours(adjustedHours)
                .businessDays(businessDays)
                .estimatedStartDate(start)
                .estimatedEndDate(endDate)
                .lines(lines)
                .build();
    }

    private static double resolvePrdTime(ProductEntity product) {
        if (product != null && product.getPrdTime() != null && product.getPrdTime() > 0) {
            return product.getPrdTime();
        }
        return ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
    }

    private EfficiencyStats computeEfficiency(LocalDate from) throws BusinessException {
        List<TaskEntity> timed = taskRepository.findCompletedWithTimingSince(from.atStartOfDay());
        if (timed.isEmpty()) {
            return new EfficiencyStats(null, 0);
        }

        int deskCount = Math.max(1, productionDeskCountService.getDay(GuatemalaDateTime.today()).getNumDesks());
        Map<Integer, List<TaskEntity>> byDesk = new HashMap<>();
        for (TaskEntity task : timed) {
            Integer desk = task.getDesk();
            if (desk == null || desk < 1 || desk > deskCount) {
                continue;
            }
            byDesk.computeIfAbsent(desk, k -> new ArrayList<>()).add(task);
        }

        List<Double> deskRates = new ArrayList<>();
        for (List<TaskEntity> deskTasks : byDesk.values()) {
            double avgEstimated = deskTasks.stream()
                    .mapToDouble(t -> t.getEstimatedHours() * 60.0)
                    .average()
                    .orElse(0);
            double avgActual = deskTasks.stream()
                    .mapToInt(TaskEntity::getActualDurationMinutes)
                    .average()
                    .orElse(0);
            if (avgActual > 0 && avgEstimated > 0) {
                double rate = round1(avgEstimated * 100.0 / avgActual);
                if (rate > 0) {
                    deskRates.add(rate);
                }
            }
        }
        if (deskRates.isEmpty()) {
            return new EfficiencyStats(null, timed.size());
        }
        double avg = deskRates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new EfficiencyStats(round1(avg), timed.size());
    }

    private static LocalDate projectEndDate(LocalDate start, double hours, int deskCount) {
        if (hours <= 0) {
            return start;
        }
        double remaining = hours;
        LocalDate cursor = start;
        int guard = 0;
        while (remaining > 0 && guard++ < MAX_DAYS) {
            double dayCap = dayCapacityHours(cursor, deskCount);
            if (dayCap <= 0) {
                cursor = cursor.plusDays(1);
                continue;
            }
            if (remaining <= dayCap) {
                return cursor;
            }
            remaining -= dayCap;
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    private static int countBusinessDays(LocalDate start, LocalDate end) {
        if (end == null || start == null || end.isBefore(start)) {
            return 0;
        }
        int days = 0;
        LocalDate cursor = start;
        int guard = 0;
        while (!cursor.isAfter(end) && guard++ < MAX_DAYS) {
            if (dayCapacityHours(cursor, 1) > 0) {
                days++;
            }
            cursor = cursor.plusDays(1);
        }
        return Math.max(days, 1);
    }

    private static double dayCapacityHours(LocalDate date, int deskCount) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return 0;
        }
        double perDesk = dow == DayOfWeek.FRIDAY ? HOURS_FRIDAY : HOURS_MON_THU;
        return perDesk * deskCount;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private record EfficiencyStats(Double percent, int measuredTasks) {}
}
