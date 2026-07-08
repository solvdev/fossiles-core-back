package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionTaskGenerationService {

    private static final double MAX_HOURS_PER_DESK_PER_DAY = 4.0;
    private static final double DEFAULT_PRD_TIME_PER_UNIT = 0.1;
    private static final int MAX_DESKS = 12;
    private static final List<String> DESKS_COUNT_CONFIG_KEYS = List.of(
            "MANUFACTURING_NUMBER_OF_TABLES",
            "PRODUCTION_TABLES_COUNT"
    );

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ColorRepository colorRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<TaskEntity> generateTasks(long productionOrderId, boolean forceRegenerate)
            throws ResourceNotFoundException, BusinessException {

        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        assertProductionOrderReadyForTasks(po);

        if (isCinchoOrderType(po.getOrderType())) {
            throw new BusinessException(
                    "Las órdenes de tipo cinchos se gestionan en la vista de Cinchos, no en el centro de producción.");
        }

        List<TaskEntity> existingTasks = taskRepository.findByProductionOrderId(productionOrderId);
        if (!existingTasks.isEmpty()) {
            RegenerationRisk risk = assessRegenerationRisk(productionOrderId, existingTasks);
            if (risk.hasRisk && !forceRegenerate) {
                throw new BusinessException(
                        "Esta OP ya tiene tareas en ejecución o mezcladas con otras órdenes. "
                                + "Para regenerar de forma explícita use force=true. Detalle: " + risk.message);
            }
            taskItemRepository.deleteByProductionOrderId(productionOrderId);
            taskRepository.deleteByProductionOrderId(productionOrderId);
        }

        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        if (items.isEmpty()) {
            throw new BusinessException("La orden de producción no tiene items para generar tareas");
        }

        List<TaskChunk> primaryChunks = buildChunksForOrder(po, items, true);
        if (primaryChunks.isEmpty()) {
            throw new BusinessException("No hay items pendientes para generar tareas en la orden.");
        }

        List<List<TaskChunk>> taskGroups = groupChunksIntoTasks(primaryChunks);

        boolean canSchedule = po.getStartDate() != null && po.getDeliveryDate() != null
                && !po.getStartDate().isAfter(po.getDeliveryDate());
        int numDesks = getNumDesks();
        SchedulingContext schedulingContext = canSchedule ? buildSchedulingContext(numDesks) : null;

        int priorityCounter = 5;
        List<TaskEntity> generatedTasks = new ArrayList<>();

        for (List<TaskChunk> group : taskGroups) {
            double totalHours = Math.round(group.stream().mapToDouble(TaskChunk::getEstimatedHours).sum() * 100.0) / 100.0;
            TaskChunk primary = group.get(0);

            TaskEntity.TaskEntityBuilder builder = TaskEntity.builder()
                    .code(generateTaskCode())
                    .productionOrderId(primary.getProductionOrderId())
                    .productionOrderCode(primary.getProductionOrderCode())
                    .productionOrderItemId(primary.getProductionOrderItemId())
                    .productId(primary.getProductId())
                    .productName(primary.getProductName())
                    .productCode(primary.getProductCode())
                    .colorId(primary.getColorId())
                    .colorName(primary.getColorName())
                    .observations(primary.getObservations())
                    .quantity(group.stream().mapToInt(TaskChunk::getQuantity).sum())
                    .estimatedHours(totalHours)
                    .deliveryDate(primary.getDeliveryDate() != null ? primary.getDeliveryDate() : po.getDeliveryDate())
                    .priority(priorityCounter++)
                    .status("PENDING");

            if (canSchedule) {
                SlotAssignment slot = findEarliestSlot(
                        schedulingContext,
                        primary.getStartDate() != null ? primary.getStartDate() : po.getStartDate(),
                        totalHours);
                schedulingContext.scheduleMap.computeIfAbsent(slot.date, k -> new HashMap<>());
                schedulingContext.scheduleMap.get(slot.date).merge(slot.desk, totalHours, Double::sum);
                builder.desk(slot.desk).scheduledDate(slot.date);
            }

            TaskEntity task = taskRepository.save(builder.build());

            for (TaskChunk chunk : group) {
                boolean requiresMaterials = chunk.getProductId() == null
                        || productRepository.findById(chunk.getProductId())
                        .map(p -> !Boolean.FALSE.equals(p.getRequiresMaterials()))
                        .orElse(true);
                taskItemRepository.save(TaskItemEntity.builder()
                        .taskId(task.getId())
                        .productionOrderItemId(chunk.getProductionOrderItemId())
                        .productId(chunk.getProductId())
                        .productCode(chunk.getProductCode())
                        .productName(chunk.getProductName())
                        .colorId(chunk.getColorId())
                        .colorName(chunk.getColorName())
                        .quantity(chunk.getQuantity())
                        .estimatedHours(chunk.getEstimatedHours())
                        .observations(chunk.getObservations())
                        .leatherDelivered(false)
                        .leatherDeliveredAt(null)
                        .materialsDelivered(!requiresMaterials)
                        .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                        .build());
            }

            generatedTasks.add(task);
        }

        Set<Long> linkedOrderIds = generatedTasks.stream()
                .map(TaskEntity::getId)
                .flatMap(taskId -> taskItemRepository.findByTaskId(taskId).stream())
                .map(TaskItemEntity::getProductionOrderItemId)
                .filter(Objects::nonNull)
                .map(itemId -> productionOrderItemRepository.findById(itemId).orElse(null))
                .filter(Objects::nonNull)
                .map(ProductionOrderItemEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        linkedOrderIds.add(po.getId());

        for (Long orderId : linkedOrderIds) {
            ProductionOrderEntity linkedOrder = productionOrderRepository.findById(orderId).orElse(null);
            if (linkedOrder != null && "PENDING".equals(linkedOrder.getStatus())) {
                linkedOrder.setStatus("IN_PROGRESS");
                productionOrderRepository.save(linkedOrder);
            }
        }

        return generatedTasks;
    }

    /**
     * Tareas ligeras solo para entrega de materiales en OP cincho (sin mesas ni centro de producción).
     * Si la OP ya tiene tareas, las devuelve sin duplicar.
     */
    @Transactional
    public List<TaskEntity> generateCinchoMaterialsTasks(long productionOrderId)
            throws ResourceNotFoundException, BusinessException {

        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        if (!isCinchoOrderType(po.getOrderType())) {
            throw new BusinessException(
                    "Solo aplica a órdenes CINCHOS, CINCHOS_FOSSILES o CINCHOS_MARCAS.");
        }

        List<TaskEntity> existingTasks = taskRepository.findByProductionOrderId(productionOrderId);
        if (!existingTasks.isEmpty()) {
            return existingTasks;
        }

        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        if (items.isEmpty()) {
            throw new BusinessException("La orden no tiene líneas para registrar entrega de materiales.");
        }

        LocalDate scheduledDate = po.getStartDate() != null ? po.getStartDate() : LocalDate.now();
        LocalDate today = LocalDate.now();
        if (scheduledDate.isBefore(today)) {
            scheduledDate = today;
        }

        int totalPieces = items.stream().mapToInt(this::calculateItemTotalQuantity).sum();

        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .code(generateTaskCode())
                .productionOrderId(po.getId())
                .productionOrderCode(po.getCode())
                .quantity(totalPieces)
                .estimatedHours(0.1)
                .deliveryDate(po.getDeliveryDate())
                .scheduledDate(scheduledDate)
                .priority(5)
                .status("PENDING")
                .observations("Materiales cincho (bodega)")
                .leatherDelivered(true)
                .leatherDeliveredAt(LocalDateTime.now())
                .dieCutReady(true)
                .build());

        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            String colorName = null;
            if (item.getColorId() != null) {
                colorName = colorRepository.findById(item.getColorId()).map(ColorEntity::getName).orElse(null);
            }
            int qty = calculateItemTotalQuantity(item);
            boolean requiresMaterials = product == null || !Boolean.FALSE.equals(product.getRequiresMaterials());

            taskItemRepository.save(TaskItemEntity.builder()
                    .taskId(task.getId())
                    .productionOrderItemId(item.getId())
                    .productId(item.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(item.getColorId())
                    .colorName(colorName)
                    .quantity(qty)
                    .estimatedHours(0.1)
                    .observations(item.getObservations())
                    .leatherDelivered(true)
                    .leatherDeliveredAt(LocalDateTime.now())
                    .materialsDelivered(!requiresMaterials)
                    .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                    .build());
        }

        return List.of(task);
    }

    /**
     * Genera tareas únicamente para los items seleccionados de una OP.
     * No elimina tareas existentes de la orden — solo agrega tareas para items que aún no las tienen.
     */
    @Transactional
    public List<TaskEntity> generateTasksForSelectedItems(long productionOrderId, List<Long> selectedItemIds)
            throws ResourceNotFoundException, BusinessException {

        if (selectedItemIds == null || selectedItemIds.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos un producto para generar tareas.");
        }

        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        assertProductionOrderReadyForTasks(po);

        if (isCinchoOrderType(po.getOrderType())) {
            throw new BusinessException(
                    "Las órdenes de tipo cinchos se gestionan en la vista de Cinchos, no en el centro de producción.");
        }

        List<ProductionOrderItemEntity> allItems = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        List<ProductionOrderItemEntity> selectedItems = allItems.stream()
                .filter(item -> selectedItemIds.contains(item.getId()))
                .collect(Collectors.toList());

        if (selectedItems.isEmpty()) {
            throw new BusinessException("No se encontraron los items seleccionados en esta orden.");
        }

        // buildChunksForOrder con skipAlreadyPlannedItems=true omite automáticamente items con tareas existentes.
        List<TaskChunk> primaryChunks = buildChunksForOrder(po, selectedItems, true);
        if (primaryChunks.isEmpty()) {
            throw new BusinessException("Los productos seleccionados ya tienen tareas generadas o no son válidos para producción.");
        }

        List<List<TaskChunk>> taskGroups = groupChunksIntoTasks(primaryChunks);

        boolean canSchedule = po.getStartDate() != null && po.getDeliveryDate() != null
                && !po.getStartDate().isAfter(po.getDeliveryDate());
        int numDesks = getNumDesks();
        SchedulingContext schedulingContext = canSchedule ? buildSchedulingContext(numDesks) : null;

        int priorityCounter = 5;
        List<TaskEntity> generatedTasks = new ArrayList<>();

        for (List<TaskChunk> group : taskGroups) {
            double totalHours = Math.round(group.stream().mapToDouble(TaskChunk::getEstimatedHours).sum() * 100.0) / 100.0;
            TaskChunk primary = group.get(0);

            TaskEntity.TaskEntityBuilder builder = TaskEntity.builder()
                    .code(generateTaskCode())
                    .productionOrderId(primary.getProductionOrderId())
                    .productionOrderCode(primary.getProductionOrderCode())
                    .productionOrderItemId(primary.getProductionOrderItemId())
                    .productId(primary.getProductId())
                    .productName(primary.getProductName())
                    .productCode(primary.getProductCode())
                    .colorId(primary.getColorId())
                    .colorName(primary.getColorName())
                    .observations(primary.getObservations())
                    .quantity(group.stream().mapToInt(TaskChunk::getQuantity).sum())
                    .estimatedHours(totalHours)
                    .deliveryDate(primary.getDeliveryDate() != null ? primary.getDeliveryDate() : po.getDeliveryDate())
                    .priority(priorityCounter++)
                    .status("PENDING");

            if (canSchedule) {
                SlotAssignment slot = findEarliestSlot(
                        schedulingContext,
                        primary.getStartDate() != null ? primary.getStartDate() : po.getStartDate(),
                        totalHours);
                schedulingContext.scheduleMap.computeIfAbsent(slot.date, k -> new HashMap<>());
                schedulingContext.scheduleMap.get(slot.date).merge(slot.desk, totalHours, Double::sum);
                builder.desk(slot.desk).scheduledDate(slot.date);
            }

            TaskEntity task = taskRepository.save(builder.build());

            for (TaskChunk chunk : group) {
                boolean requiresMaterials = chunk.getProductId() == null
                        || productRepository.findById(chunk.getProductId())
                        .map(p -> !Boolean.FALSE.equals(p.getRequiresMaterials()))
                        .orElse(true);
                taskItemRepository.save(TaskItemEntity.builder()
                        .taskId(task.getId())
                        .productionOrderItemId(chunk.getProductionOrderItemId())
                        .productId(chunk.getProductId())
                        .productCode(chunk.getProductCode())
                        .productName(chunk.getProductName())
                        .colorId(chunk.getColorId())
                        .colorName(chunk.getColorName())
                        .quantity(chunk.getQuantity())
                        .estimatedHours(chunk.getEstimatedHours())
                        .observations(chunk.getObservations())
                        .leatherDelivered(false)
                        .leatherDeliveredAt(null)
                        .materialsDelivered(!requiresMaterials)
                        .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                        .build());
            }

            generatedTasks.add(task);
        }

        if ("PENDING".equals(po.getStatus())) {
            po.setStatus("IN_PROGRESS");
            productionOrderRepository.save(po);
        }

        return generatedTasks;
    }

    private RegenerationRisk assessRegenerationRisk(Long productionOrderId, List<TaskEntity> existingTasks) {
        boolean hasInProgress = existingTasks.stream()
                .anyMatch(t -> "IN_PROGRESS".equals(t.getStatus()) || "COMPLETED".equals(t.getStatus()));

        List<Long> taskIds = existingTasks.stream().map(TaskEntity::getId).toList();
        List<TaskItemEntity> taskItems = taskIds.isEmpty()
                ? List.of()
                : taskItemRepository.findByTaskIdIn(taskIds);

        Set<Long> orderItemIds = taskItems.stream()
                .map(TaskItemEntity::getProductionOrderItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Long> itemToOrder = new HashMap<>();
        if (!orderItemIds.isEmpty()) {
            productionOrderItemRepository.findAllById(orderItemIds).forEach(i ->
                    itemToOrder.put(i.getId(), i.getProductionOrderId()));
        }

        boolean hasMixedOrders = taskItems.stream()
                .map(TaskItemEntity::getProductionOrderItemId)
                .filter(Objects::nonNull)
                .map(itemToOrder::get)
                .filter(Objects::nonNull)
                .anyMatch(orderId -> !Objects.equals(orderId, productionOrderId));

        if (!hasInProgress && !hasMixedOrders) {
            return new RegenerationRisk(false, "");
        }

        StringBuilder sb = new StringBuilder();
        if (hasInProgress) {
            sb.append("Hay tareas IN_PROGRESS/COMPLETED. ");
        }
        if (hasMixedOrders) {
            sb.append("Hay tareas mixtas con otras órdenes.");
        }
        return new RegenerationRisk(true, sb.toString().trim());
    }

    private static void assertProductionOrderReadyForTasks(ProductionOrderEntity po) throws BusinessException {
        if (po == null) {
            return;
        }
        if ("DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim())) {
            String code = po.getCode() != null ? po.getCode() : "OPI";
            throw new BusinessException(
                    "La orden " + code + " está en borrador. Contabilidad debe autorizar la producción "
                            + "desde Autorizar envíos internos antes de generar tareas.");
        }
    }

    private static boolean isCinchoOrderType(String orderType) {
        if (orderType == null || orderType.isBlank()) {
            return false;
        }
        String t = orderType.trim().toUpperCase(Locale.ROOT);
        return "CINCHOS".equals(t) || "CINCHOS_FOSSILES".equals(t) || "CINCHOS_MARCAS".equals(t);
    }

    private List<TaskChunk> buildChunksForOrder(
            ProductionOrderEntity order,
            List<ProductionOrderItemEntity> items,
            boolean skipAlreadyPlannedItems) {
        List<TaskChunk> chunks = new ArrayList<>();

        for (ProductionOrderItemEntity item : items) {
            if (skipAlreadyPlannedItems && taskItemRepository.existsByProductionOrderItemId(item.getId())) {
                continue;
            }

            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;

            if (!isCinchoOrderType(order.getOrderType()) && CinchoProductUtils.isFossCinchoProduct(product)) {
                continue;
            }

            String colorName = null;
            if (item.getColorId() != null) {
                ColorEntity color = colorRepository.findById(item.getColorId()).orElse(null);
                colorName = color != null ? color.getName() : null;
            }

            double prdTimePerUnit = (product != null && product.getPrdTime() != null && product.getPrdTime() > 0)
                    ? product.getPrdTime()
                    : DEFAULT_PRD_TIME_PER_UNIT;

            int totalQuantity = calculateItemTotalQuantity(item);
            int remainingQty = totalQuantity;
            while (remainingQty > 0) {
                int maxUnitsPerBlock = (int) Math.floor(MAX_HOURS_PER_DESK_PER_DAY / prdTimePerUnit);
                if (maxUnitsPerBlock <= 0) maxUnitsPerBlock = 1;
                int chunkQty = Math.min(remainingQty, maxUnitsPerBlock);
                double chunkHours = Math.round(chunkQty * prdTimePerUnit * 100.0) / 100.0;

                chunks.add(TaskChunk.builder()
                        .productionOrderId(order.getId())
                        .productionOrderCode(order.getCode())
                        .orderType(order.getOrderType())
                        .startDate(order.getStartDate())
                        .deliveryDate(order.getDeliveryDate())
                        .productionOrderItemId(item.getId())
                        .productId(item.getProductId())
                        .productName(product != null ? product.getName() : null)
                        .productCode(product != null ? product.getCode() : null)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .observations(item.getObservations())
                        .quantity(chunkQty)
                        .estimatedHours(chunkHours)
                        .build());

                remainingQty -= chunkQty;
            }
        }

        return chunks;
    }

    private List<List<TaskChunk>> groupChunksIntoTasks(List<TaskChunk> chunks) {
        List<List<TaskChunk>> taskGroups = new ArrayList<>();
        List<TaskChunk> currentGroup = new ArrayList<>();
        double currentGroupHours = 0;

        for (TaskChunk chunk : chunks) {
            if (!currentGroup.isEmpty() && currentGroupHours + chunk.getEstimatedHours() > MAX_HOURS_PER_DESK_PER_DAY) {
                taskGroups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentGroupHours = 0;
            }
            currentGroup.add(chunk);
            currentGroupHours += chunk.getEstimatedHours();
        }
        if (!currentGroup.isEmpty()) {
            taskGroups.add(currentGroup);
        }
        return taskGroups;
    }

    private SchedulingContext buildSchedulingContext(int numDesks) {
        Map<LocalDate, Map<Integer, Double>> scheduleMap = new HashMap<>();
        List<TaskEntity> activeTasks = taskRepository.findPendingAndInProgressOrdered();

        for (TaskEntity task : activeTasks) {
            if ("CANCELLED".equals(task.getStatus())) continue;

            if (task.getScheduledDate() != null && task.getDesk() != null && task.getEstimatedHours() != null) {
                double baseHours = getTaskBaseHours(task);
                scheduleMap.computeIfAbsent(task.getScheduledDate(), k -> new HashMap<>());
                scheduleMap.get(task.getScheduledDate())
                        .merge(task.getDesk(), baseHours, Double::sum);
            }
        }

        return new SchedulingContext(scheduleMap, numDesks);
    }

    private SlotAssignment findEarliestSlot(SchedulingContext context,
                                           LocalDate startDate, double requiredHours) {
        LocalDate currentDate = startDate;
        int maxDaysSearch = 365;

        for (int dayOffset = 0; dayOffset < maxDaysSearch; dayOffset++) {
            if (currentDate.getDayOfWeek() == DayOfWeek.SATURDAY || currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                currentDate = currentDate.plusDays(1);
                continue;
            }

            Map<Integer, Double> daySchedule = context.scheduleMap.getOrDefault(currentDate, new HashMap<>());

            Integer bestDesk = null;
            double bestLoad = Double.MAX_VALUE;
            for (int desk = 1; desk <= context.numDesks; desk++) {
                double usedHours = daySchedule.getOrDefault(desk, 0.0);
                double availableHours = MAX_HOURS_PER_DESK_PER_DAY - usedHours;
                if (availableHours >= requiredHours) {
                    if (usedHours < bestLoad - 1e-9 || (Math.abs(usedHours - bestLoad) < 1e-9 && (bestDesk == null || desk < bestDesk))) {
                        bestLoad = usedHours;
                        bestDesk = desk;
                    }
                }
            }
            if (bestDesk != null) {
                return new SlotAssignment(currentDate, bestDesk);
            }

            currentDate = currentDate.plusDays(1);
        }

        return new SlotAssignment(startDate, 1);
    }

    private int calculateItemTotalQuantity(ProductionOrderItemEntity item) {
        int total = 0;
        if (item.getQuantity() != null) {
            total += item.getQuantity();
        }
        if (item.getSizesData() != null && !item.getSizesData().isEmpty()) {
            try {
                Map<String, Integer> sizes = objectMapper.readValue(item.getSizesData(),
                        new TypeReference<Map<String, Integer>>() {});
                total += sizes.values().stream().mapToInt(Integer::intValue).sum();
            } catch (Exception ignored) {
            }
        }
        return Math.max(total, 1);
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
            }
        }

        return new DesksCountResolution(MAX_DESKS, "DEFAULT", true);
    }

    private int getNumDesks() {
        return resolveNumDesks().count();
    }

    private double getTaskBaseHours(TaskEntity task) {
        if (task == null) return 0.0;
        double total = task.getEstimatedHours() != null ? task.getEstimatedHours() : 0.0;
        if (task.getId() == null) return total;
        double extra = taskItemRepository.findByTaskId(task.getId()).stream()
                .filter(item -> Boolean.TRUE.equals(item.getDaySaleExtra()))
                .map(TaskItemEntity::getEstimatedHours)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return Math.max(total - extra, 0.0);
    }

    private String generateTaskCode() throws BusinessException {
        String documentType = "TASK";
        String series = "TK";

        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> {
                    DocumentSeriesEntity newSeries = DocumentSeriesEntity.builder()
                            .documentType(documentType)
                            .series(series)
                            .currentCorrelative(0L)
                            .status("active")
                            .description("Serie automática para tareas de producción")
                            .build();
                    return documentSeriesRepository.save(newSeries);
                });

        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);

        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }

    @Builder
    @Data
    private static class TaskChunk {
        private Long productionOrderId;
        private String productionOrderCode;
        private String orderType;
        private LocalDate startDate;
        private LocalDate deliveryDate;
        private Long productionOrderItemId;
        private Long productId;
        private String productName;
        private String productCode;
        private Long colorId;
        private String colorName;
        private String observations;
        private int quantity;
        private double estimatedHours;
    }

    private record SlotAssignment(LocalDate date, int desk) {}

    private record SchedulingContext(
            Map<LocalDate, Map<Integer, Double>> scheduleMap,
            int numDesks) {}

    private record RegenerationRisk(boolean hasRisk, String message) {}
}
