package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.MaterialsCinchoOrderResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialsTaskViewResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemMaterialPickEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemMaterialPickRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Arma la vista de materiales con unas pocas consultas, no una por tarea ni por línea de receta.
 * Las reglas (pendiente, cuero, troquel, BOM por color) son las mismas que tenía el controlador.
 */
@Service
@RequiredArgsConstructor
public class MaterialsTaskViewService {

    private static final int CHUNK = 500;
    private static final String BOM_ACTIVE = "A";

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final BomRepository bomRepository;
    private final BomItemRepository bomItemRepository;
    private final MaterialRepository materialRepository;
    private final TaskItemMaterialPickRepository taskItemMaterialPickRepository;

    public List<MaterialsTaskViewResponse> build(List<TaskEntity> tasks, boolean onlyPending) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        ViewData data = ViewData.load(this, tasks);
        List<TaskEntity> selected = new ArrayList<>();
        for (TaskEntity task : tasks) {
            if (!onlyPending || data.isPending(task)) {
                selected.add(task);
            }
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        data.loadRecipes(this, selected);
        List<MaterialsTaskViewResponse> views = new ArrayList<>();
        for (TaskEntity task : selected) {
            MaterialsTaskViewResponse view = data.toView(task);
            if (view.getProducts() != null && !view.getProducts().isEmpty()) {
                views.add(view);
            }
        }
        return views;
    }

    public List<MaterialsCinchoOrderResponse> listCinchoOrdersWithPendingMaterials() {
        List<ProductionOrderEntity> orders = productionOrderRepository.findOpenCinchoOrders();
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).filter(Objects::nonNull).toList();
        Map<Long, TaskEntity> tasksById = new LinkedHashMap<>();
        for (TaskEntity task : byIds(orderIds, taskRepository::findByProductionOrderIdIn)) {
            if (task.getId() != null) {
                tasksById.put(task.getId(), task);
            }
        }
        Map<Long, Set<Long>> orderIdsByTaskId = new HashMap<>();
        for (Object[] row : byIds(orderIds, taskItemRepository::findTaskLinksByProductionOrderIdIn)) {
            Long taskId = row[0] == null ? null : ((Number) row[0]).longValue();
            Long orderId = row[1] == null ? null : ((Number) row[1]).longValue();
            if (taskId == null || orderId == null) {
                continue;
            }
            orderIdsByTaskId.computeIfAbsent(taskId, k -> new HashSet<>()).add(orderId);
        }
        List<Long> missingTaskIds = orderIdsByTaskId.keySet().stream()
                .filter(id -> !tasksById.containsKey(id))
                .toList();
        for (TaskEntity task : byIds(missingTaskIds, ids -> {
            List<TaskEntity> found = new ArrayList<>();
            taskRepository.findAllById(ids).forEach(found::add);
            return found;
        })) {
            if (task.getId() != null) {
                tasksById.put(task.getId(), task);
            }
        }
        if (tasksById.isEmpty()) {
            return List.of();
        }

        Set<Long> ordersWithPending = new HashSet<>();
        for (MaterialsTaskViewResponse view : build(new ArrayList<>(tasksById.values()), true)) {
            if (view.getProductionOrderId() != null) {
                ordersWithPending.add(view.getProductionOrderId());
            }
            Set<Long> linked = orderIdsByTaskId.get(view.getTaskId());
            if (linked != null) {
                ordersWithPending.addAll(linked);
            }
        }

        List<MaterialsCinchoOrderResponse> cards = new ArrayList<>();
        for (ProductionOrderEntity order : orders) {
            if (!ordersWithPending.contains(order.getId())) {
                continue;
            }
            cards.add(MaterialsCinchoOrderResponse.builder()
                    .id(order.getId())
                    .code(order.getCode())
                    .orderType(order.getOrderType())
                    .status(order.getStatus())
                    .customerName(order.getCustomerName())
                    .startDate(order.getStartDate())
                    .deliveryDate(order.getDeliveryDate())
                    .build());
        }
        return cards;
    }

    private <T> List<T> byIds(Collection<Long> ids, Function<List<Long>, List<T>> query) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i += CHUNK) {
            out.addAll(query.apply(distinct.subList(i, Math.min(i + CHUNK, distinct.size()))));
        }
        return out;
    }

    private static final class ViewData {
        private final Map<Long, List<TaskItemEntity>> itemsByTask;
        private final Map<Long, ProductionOrderEntity> ordersById;
        private final Map<Long, ProductEntity> productsById;
        private final Map<Long, ProductionOrderItemEntity> orderItemsById;
        private Map<Long, List<BomEntity>> bomsByProduct = Map.of();
        private Map<Long, List<BomItemEntity>> bomItemsByBom = Map.of();
        private Map<Long, MaterialEntity> materialsById = Map.of();
        private Map<Long, Map<Long, TaskItemMaterialPickEntity>> picksByItem = Map.of();

        private ViewData(
                Map<Long, List<TaskItemEntity>> itemsByTask,
                Map<Long, ProductionOrderEntity> ordersById,
                Map<Long, ProductEntity> productsById,
                Map<Long, ProductionOrderItemEntity> orderItemsById) {
            this.itemsByTask = itemsByTask;
            this.ordersById = ordersById;
            this.productsById = productsById;
            this.orderItemsById = orderItemsById;
        }

        static ViewData load(MaterialsTaskViewService svc, List<TaskEntity> tasks) {
            List<Long> taskIds = tasks.stream().map(TaskEntity::getId).filter(Objects::nonNull).distinct().toList();
            Map<Long, List<TaskItemEntity>> itemsByTask = svc.byIds(taskIds, svc.taskItemRepository::findByTaskIdIn)
                    .stream()
                    .filter(item -> item.getTaskId() != null)
                    .collect(Collectors.groupingBy(TaskItemEntity::getTaskId));

            List<Long> orderIds = tasks.stream().map(TaskEntity::getProductionOrderId).filter(Objects::nonNull).distinct().toList();
            Map<Long, ProductionOrderEntity> ordersById = svc.byIds(orderIds, ids -> {
                List<ProductionOrderEntity> found = new ArrayList<>();
                svc.productionOrderRepository.findAllById(ids).forEach(found::add);
                return found;
            }).stream().collect(Collectors.toMap(ProductionOrderEntity::getId, o -> o, (a, b) -> a));

            Set<Long> productIds = new HashSet<>();
            Set<Long> orderItemIds = new HashSet<>();
            for (TaskEntity task : tasks) {
                if (task.getProductId() != null) {
                    productIds.add(task.getProductId());
                }
                for (TaskItemEntity item : itemsByTask.getOrDefault(task.getId(), List.of())) {
                    if (item.getProductId() != null) {
                        productIds.add(item.getProductId());
                    }
                    if (item.getProductionOrderItemId() != null) {
                        orderItemIds.add(item.getProductionOrderItemId());
                    }
                }
            }

            Map<Long, ProductEntity> productsById = svc.byIds(productIds, ids -> {
                List<ProductEntity> found = new ArrayList<>();
                svc.productRepository.findAllById(ids).forEach(found::add);
                return found;
            }).stream().collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));

            Map<Long, ProductionOrderItemEntity> orderItemsById = svc.byIds(orderItemIds, ids -> {
                List<ProductionOrderItemEntity> found = new ArrayList<>();
                svc.productionOrderItemRepository.findAllById(ids).forEach(found::add);
                return found;
            }).stream().collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));

            return new ViewData(itemsByTask, ordersById, productsById, orderItemsById);
        }

        void loadRecipes(MaterialsTaskViewService svc, List<TaskEntity> tasks) {
            Set<Long> productIds = new HashSet<>();
            List<Long> taskItemIds = new ArrayList<>();
            for (TaskEntity task : tasks) {
                if (task.getProductId() != null) {
                    productIds.add(task.getProductId());
                }
                for (TaskItemEntity item : itemsOf(task)) {
                    if (item.getProductId() != null) {
                        productIds.add(item.getProductId());
                    }
                    if (item.getId() != null) {
                        taskItemIds.add(item.getId());
                    }
                }
            }

            bomsByProduct = svc.byIds(productIds, ids -> svc.bomRepository.findByProductIdInAndStatus(ids, BOM_ACTIVE))
                    .stream()
                    .filter(bom -> bom.getProductId() != null)
                    .collect(Collectors.groupingBy(BomEntity::getProductId));

            List<Long> bomIds = bomsByProduct.values().stream()
                    .flatMap(List::stream)
                    .map(BomEntity::getId)
                    .filter(Objects::nonNull)
                    .toList();
            bomItemsByBom = svc.byIds(bomIds, svc.bomItemRepository::findByBomIdIn)
                    .stream()
                    .filter(item -> item.getBomId() != null)
                    .collect(Collectors.groupingBy(BomItemEntity::getBomId));

            List<Long> materialIds = bomItemsByBom.values().stream()
                    .flatMap(List::stream)
                    .map(BomItemEntity::getMaterialId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            materialsById = svc.byIds(materialIds, ids -> {
                List<MaterialEntity> found = new ArrayList<>();
                svc.materialRepository.findAllById(ids).forEach(found::add);
                return found;
            }).stream().collect(Collectors.toMap(MaterialEntity::getId, m -> m, (a, b) -> a));

            Map<Long, Map<Long, TaskItemMaterialPickEntity>> picks = new HashMap<>();
            for (TaskItemMaterialPickEntity pick : svc.byIds(taskItemIds, svc.taskItemMaterialPickRepository::findByTaskItemIdIn)) {
                if (pick.getTaskItemId() == null || pick.getMaterialId() == null) {
                    continue;
                }
                picks.computeIfAbsent(pick.getTaskItemId(), k -> new HashMap<>())
                        .putIfAbsent(pick.getMaterialId(), pick);
            }
            picksByItem = picks;
        }

        boolean isPending(TaskEntity task) {
            if (task == null || "CANCELLED".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus())) {
                return false;
            }
            if (task.getProductionOrderId() != null) {
                ProductionOrderEntity order = ordersById.get(task.getProductionOrderId());
                if (order != null && ("COMPLETED".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus()))) {
                    return false;
                }
            }
            List<TaskItemEntity> items = itemsOf(task);
            return requiresMaterials(task, items) && !requiredItemsDelivered(task, items);
        }

        MaterialsTaskViewResponse toView(TaskEntity task) {
            ProductionOrderEntity order = task.getProductionOrderId() != null
                    ? ordersById.get(task.getProductionOrderId())
                    : null;
            List<TaskItemEntity> items = itemsOf(task);
            List<MaterialsTaskViewResponse.TaskProductWithRecipe> products;
            if (!items.isEmpty()) {
                products = items.stream()
                        .map(item -> productLine(task, item))
                        .filter(p -> !Boolean.FALSE.equals(p.getRequiresMaterials()))
                        .toList();
            } else if (task.getProductId() != null) {
                TaskItemEntity legacy = TaskItemEntity.builder()
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
                products = itemRequiresMaterials(legacy) ? List.of(productLine(task, legacy)) : List.of();
            } else {
                products = List.of();
            }

            return MaterialsTaskViewResponse.builder()
                    .taskId(task.getId())
                    .taskCode(task.getCode())
                    .productionOrderCode(task.getProductionOrderCode())
                    .productionOrderId(task.getProductionOrderId())
                    .customerName(order != null ? order.getCustomerName() : null)
                    .orderType(order != null ? order.getOrderType() : null)
                    .desk(task.getDesk())
                    .scheduledDate(task.getScheduledDate())
                    .startTime(task.getStartTime())
                    .estimatedHours(task.getEstimatedHours())
                    .status(task.getStatus())
                    .leatherDelivered(task.getLeatherDelivered())
                    .leatherDeliveredAt(task.getLeatherDeliveredAt())
                    .dieCutReady(task.getDieCutReady())
                    .dieCutDate(task.getDieCutDate())
                    .materialsDelivered(requiredItemsDelivered(task, items))
                    .materialsDeliveredAt(task.getMaterialsDeliveredAt())
                    .requiresMaterials(requiresMaterials(task, items))
                    .workflowStatus(workflowStatus(task, items))
                    .canDeliverMaterials(canDeliver(task, items))
                    .completedAt(task.getCompletedAt())
                    .products(products)
                    .build();
        }

        private List<TaskItemEntity> itemsOf(TaskEntity task) {
            if (task == null || task.getId() == null) {
                return List.of();
            }
            return itemsByTask.getOrDefault(task.getId(), List.of());
        }

        private MaterialsTaskViewResponse.TaskProductWithRecipe productLine(TaskEntity task, TaskItemEntity item) {
            Long productId = item.getProductId();
            Long colorId = item.getColorId();
            int quantity = recipeQuantity(item);
            Map<Long, TaskItemMaterialPickEntity> picks = item.getId() == null
                    ? Map.of()
                    : picksByItem.getOrDefault(item.getId(), Map.of());

            List<MaterialsTaskViewResponse.RecipeMaterial> recipe = List.of();
            if (productId != null) {
                List<BomEntity> boms = bomsByProduct.getOrDefault(productId, List.of());
                BomEntity matched = boms.stream()
                        .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                        .findFirst()
                        .orElse(boms.isEmpty() ? null : boms.get(0));
                if (matched != null) {
                    recipe = bomItemsByBom.getOrDefault(matched.getId(), List.of()).stream()
                            .map(bomItem -> recipeLine(bomItem, quantity, picks.get(bomItem.getMaterialId())))
                            .toList();
                }
            }

            boolean requires = itemRequiresMaterials(item);
            return MaterialsTaskViewResponse.TaskProductWithRecipe.builder()
                    .taskItemId(item.getId())
                    .productId(productId)
                    .productCode(item.getProductCode())
                    .productName(item.getProductName())
                    .colorId(colorId)
                    .colorName(item.getColorName())
                    .quantity(quantity)
                    .requiresMaterials(requires)
                    .leatherDelivered(Boolean.TRUE.equals(item.getLeatherDelivered()) || Boolean.TRUE.equals(task.getLeatherDelivered()))
                    .leatherDeliveredAt(item.getLeatherDeliveredAt() != null ? item.getLeatherDeliveredAt() : task.getLeatherDeliveredAt())
                    .materialsDelivered(Boolean.TRUE.equals(item.getMaterialsDelivered()) || !requires)
                    .materialsDeliveredAt(item.getMaterialsDeliveredAt())
                    .canDeliverMaterials(canDeliverItem(task, item))
                    .recipe(recipe)
                    .build();
        }

        private MaterialsTaskViewResponse.RecipeMaterial recipeLine(
                BomItemEntity bomItem, int quantity, TaskItemMaterialPickEntity pick) {
            MaterialEntity material = bomItem.getMaterialId() != null ? materialsById.get(bomItem.getMaterialId()) : null;
            BigDecimal totalQty = bomItem.getQuantity() != null
                    ? bomItem.getQuantity().multiply(BigDecimal.valueOf(quantity))
                    : BigDecimal.ZERO;
            BigDecimal available = material != null && material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
            return MaterialsTaskViewResponse.RecipeMaterial.builder()
                    .materialId(bomItem.getMaterialId())
                    .materialName(material != null ? material.getName() : null)
                    .materialSku(material != null ? material.getSku() : null)
                    .quantityPerUnit(bomItem.getQuantity())
                    .totalQuantity(totalQty)
                    .availableStock(available)
                    .sufficientStock(available.compareTo(totalQty) >= 0)
                    .measurementUnit(bomItem.getMeasurementUnit())
                    .picked(pick != null && Boolean.TRUE.equals(pick.getPicked()))
                    .pickedAt(pick != null ? pick.getPickedAt() : null)
                    .build();
        }

        private int recipeQuantity(TaskItemEntity item) {
            if (item.getProductionOrderItemId() != null) {
                ProductionOrderItemEntity orderItem = orderItemsById.get(item.getProductionOrderItemId());
                if (orderItem != null) {
                    return ProductionOrderItemQuantityHelper.effectiveQuantityForBom(orderItem);
                }
                return item.getQuantity() != null ? item.getQuantity() : 1;
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            return qty > 0 ? qty : 1;
        }

        private boolean itemRequiresMaterials(TaskItemEntity item) {
            if (item == null || item.getProductId() == null) {
                return true;
            }
            ProductEntity product = productsById.get(item.getProductId());
            if (product == null) {
                return true;
            }
            return !Boolean.FALSE.equals(product.getRequiresMaterials());
        }

        private boolean requiresMaterials(TaskEntity task, List<TaskItemEntity> items) {
            if (task == null) {
                return true;
            }
            if (items != null && !items.isEmpty()) {
                return items.stream().anyMatch(this::itemRequiresMaterials);
            }
            if (task.getProductId() == null) {
                return true;
            }
            ProductEntity product = productsById.get(task.getProductId());
            if (product == null) {
                return true;
            }
            return !Boolean.FALSE.equals(product.getRequiresMaterials());
        }

        private boolean requiredItemsDelivered(TaskEntity task, List<TaskItemEntity> items) {
            if (items == null || items.isEmpty()) {
                return !requiresMaterials(task, items) || Boolean.TRUE.equals(task.getMaterialsDelivered());
            }
            for (TaskItemEntity item : items) {
                if (itemRequiresMaterials(item) && !Boolean.TRUE.equals(item.getMaterialsDelivered())) {
                    return false;
                }
            }
            return true;
        }

        private boolean canDeliver(TaskEntity task, List<TaskItemEntity> items) {
            if (task == null || "CANCELLED".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus())) {
                return false;
            }
            if (!requiresMaterials(task, items)) {
                return true;
            }
            return !Boolean.TRUE.equals(task.getMaterialsDelivered());
        }

        private boolean canDeliverItem(TaskEntity task, TaskItemEntity item) {
            if (task == null || "CANCELLED".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus())) {
                return false;
            }
            if (!itemRequiresMaterials(item)) {
                return true;
            }
            return !Boolean.TRUE.equals(item.getMaterialsDelivered());
        }

        private String workflowStatus(TaskEntity task, List<TaskItemEntity> items) {
            if ("CANCELLED".equals(task.getStatus())) {
                return "CANCELLED";
            }
            if (!Boolean.TRUE.equals(task.getLeatherDelivered())) {
                return "PENDING_LEATHER";
            }
            if (!Boolean.TRUE.equals(task.getDieCutReady())) {
                return "PENDING_DIE_CUT";
            }
            boolean onTable = task.getDesk() != null
                    || task.getScheduledDate() != null
                    || (task.getStartTime() != null && !task.getStartTime().isBlank());
            if (!onTable) {
                return "PENDING_TABLE_ENTRY";
            }
            if (requiresMaterials(task, items) && !requiredItemsDelivered(task, items)) {
                return "PENDING_MATERIAL_DELIVERY";
            }
            if (ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE.equals(task.getStatus())) {
                return "PENDING_WAREHOUSE_RECEIPT";
            }
            if ("COMPLETED".equals(task.getStatus())) {
                return "COMPLETED";
            }
            if ("IN_PROGRESS".equals(task.getStatus())) {
                return "IN_PRODUCTION";
            }
            return "READY_TO_START";
        }
    }
}
