package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderItemsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderPlanPriority;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Consultas ligeras de «Armar el día»: resumen de OPs con trabajo pendiente (sin ítems) y los
 * ítems de UNA OP, paginados. Evita cargar todas las OPs con todos sus ítems de una vez.
 */
@Service
@RequiredArgsConstructor
public class DayPlanOrderQueryService {

    private static final Set<String> TIPOS_VALIDOS =
            Set.of("ALL", "REGULAR", "OPL", "OPK", "OPV", "OPI", "OPCK", "OPD");
    private static final int MAX_PAGE_SIZE = 200;

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final TaskItemRepository taskItemRepository;

    /** Una línea de OP ya medida. */
    private record Line(ProductionOrderItemEntity item, ProductEntity product, int total, int assigned,
                        double perUnit) {
        int remaining() {
            return Math.max(0, total - assigned);
        }
    }

    /**
     * OPs con restante por fabricar, en el orden del planificador, solo con totales.
     * Una consulta de ítems, una de productos y una de asignado para todas las OPs filtradas.
     */
    @Transactional(readOnly = true)
    public DayPlanOrderSummaryResponse.Page listOrders(String type, String search, int page, int size)
            throws BusinessException {
        String normalizedType = type == null || type.isBlank() ? "ALL" : type.trim().toUpperCase(Locale.ROOT);
        if (!TIPOS_VALIDOS.contains(normalizedType)) {
            throw new BusinessException("Tipo de orden no válido: " + type
                    + ". Valores aceptados: " + String.join(", ", TIPOS_VALIDOS));
        }
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        List<ProductionOrderEntity> orders = productionOrderRepository.findActiveOrders().stream()
                .filter(po -> isWorkable(po))
                .filter(po -> switch (normalizedType) {
                    case "ALL" -> true;
                    case "OPL" -> isOnline(po);
                    case "REGULAR" -> !isOnline(po);
                    default -> normalizedType.equals(
                            ProductionPlanningConstants.orderFamilyLabel(po.getOrderType(), po.getCode()));
                })
                .filter(po -> q.isEmpty()
                        || String.valueOf(po.getCode()).toLowerCase(Locale.ROOT).contains(q)
                        || String.valueOf(po.getCustomerName()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(ProductionOrderPlanPriority.comparator())
                .toList();

        Map<Long, List<Line>> linesByOrder = loadLines(orders);
        List<DayPlanOrderSummaryResponse> all = new ArrayList<>();
        for (ProductionOrderEntity po : orders) {
            List<Line> lines = linesByOrder.getOrDefault(po.getId(), List.of());
            double total = 0;
            double remaining = 0;
            int remainingItems = 0;
            for (Line l : lines) {
                total += l.total() * l.perUnit();
                remaining += l.remaining() * l.perUnit();
                if (l.remaining() > 0) {
                    remainingItems++;
                }
            }
            if (remainingItems == 0) {
                continue;
            }
            all.add(DayPlanOrderSummaryResponse.builder()
                    .id(po.getId()).code(po.getCode()).orderType(po.getOrderType())
                    .family(ProductionPlanningConstants.orderFamilyLabel(po.getOrderType(), po.getCode()))
                    .onlineSale(isOnline(po)).status(po.getStatus()).customerName(po.getCustomerName())
                    .deliveryDate(po.getDeliveryDate())
                    .totalHours(round2(total)).remainingHours(round2(remaining))
                    .itemCount(lines.size()).remainingItemCount(remainingItems)
                    .build());
        }

        int totalPages = (int) Math.ceil(all.size() / (double) pageSize);
        int from = Math.min(pageIndex * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return DayPlanOrderSummaryResponse.Page.builder()
                .content(all.subList(from, to)).totalElements(all.size()).totalPages(totalPages)
                .size(pageSize).number(pageIndex).first(pageIndex == 0).last(pageIndex >= totalPages - 1)
                .build();
    }

    /** Ítems de UNA OP con restante (los ya cubiertos se omiten salvo {@code includeCovered}). */
    @Transactional(readOnly = true)
    public DayPlanOrderItemsResponse listItems(Long orderId, boolean includeCovered, int page, int size)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity po = productionOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", orderId));
        if (!isWorkable(po)) {
            throw new BusinessException("La orden " + po.getCode() + " no admite tareas en el centro de producción.");
        }
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        List<Line> lines = loadLines(List.of(po)).getOrDefault(po.getId(), List.of()).stream()
                .filter(l -> includeCovered || l.remaining() > 0)
                .sorted(Comparator.comparing(l -> l.item().getId()))
                .toList();

        int totalPages = (int) Math.ceil(lines.size() / (double) pageSize);
        int from = Math.min(pageIndex * pageSize, lines.size());
        int to = Math.min(from + pageSize, lines.size());
        List<Line> slice = lines.subList(from, to);

        Set<Long> colorIds = slice.stream().map(l -> l.item().getColorId()).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> colorNames = colorIds.isEmpty() ? Map.of()
                : colorRepository.findAllById(colorIds).stream()
                        .collect(Collectors.toMap(ColorEntity::getId, ColorEntity::getName, (a, b) -> a));

        List<DayPlanOrderItemsResponse.Item> content = slice.stream().map(l -> DayPlanOrderItemsResponse.Item.builder()
                .productionOrderItemId(l.item().getId())
                .productId(l.item().getProductId())
                .productCode(l.product() != null ? l.product().getCode() : null)
                .productName(l.product() != null ? l.product().getName() : null)
                .colorId(l.item().getColorId())
                .colorName(l.item().getColorId() != null ? colorNames.get(l.item().getColorId()) : null)
                .totalQuantity(l.total()).assignedQuantity(l.assigned()).remainingQuantity(l.remaining())
                .prdTimePerUnit(l.perUnit())
                .unitsPerTask(l.product() != null ? l.product().getUnitsPerTask() : null)
                .remainingHours(round2(l.remaining() * l.perUnit()))
                .observations(l.item().getObservations())
                .build()).toList();

        return DayPlanOrderItemsResponse.builder()
                .orderId(po.getId()).orderCode(po.getCode()).onlineSale(isOnline(po))
                .content(content).totalElements(lines.size()).totalPages(totalPages)
                .size(pageSize).number(pageIndex).first(pageIndex == 0).last(pageIndex >= totalPages - 1)
                .build();
    }

    // ---------------------------------------------------------------- helpers

    private Map<Long, List<Line>> loadLines(Collection<ProductionOrderEntity> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).toList();
        Map<Long, ProductionOrderEntity> byId = orders.stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, o -> o));
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderIdIn(orderIds);
        List<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).toList();
        Set<Long> productIds = items.stream().map(ProductionOrderItemEntity::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ProductEntity> products = productIds.isEmpty() ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        Map<Long, Integer> assigned = itemIds.isEmpty() ? Map.of() : taskItemRepository.assignedQuantityMap(itemIds);

        Map<Long, List<Line>> out = new HashMap<>();
        for (ProductionOrderItemEntity item : items) {
            int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            if (total <= 0) {
                continue;
            }
            ProductEntity product = item.getProductId() != null ? products.get(item.getProductId()) : null;
            if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                continue; // los cinchos van a su propia vista
            }
            ProductionOrderEntity po = byId.get(item.getProductionOrderId());
            if (po == null) {
                continue;
            }
            double perUnit = product != null && product.getPrdTime() != null && product.getPrdTime() > 0
                    ? product.getPrdTime() : ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
            out.computeIfAbsent(po.getId(), k -> new ArrayList<>())
                    .add(new Line(item, product, total, assigned.getOrDefault(item.getId(), 0), perUnit));
        }
        return out;
    }

    private static boolean isOnline(ProductionOrderEntity po) {
        return ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode());
    }

    /** OP abierta, autorizada y que no es de cinchos. */
    private static boolean isWorkable(ProductionOrderEntity po) {
        String status = String.valueOf(po.getStatus()).trim();
        if ("COMPLETED".equals(po.getStatus()) || "CANCELLED".equals(po.getStatus())
                || "DRAFT".equalsIgnoreCase(status)) {
            return false;
        }
        String t = po.getOrderType() == null ? "" : po.getOrderType().trim().toUpperCase(Locale.ROOT);
        return !("CINCHOS".equals(t) || "CINCHOS_FOSSILES".equals(t) || "CINCHOS_MARCAS".equals(t));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
