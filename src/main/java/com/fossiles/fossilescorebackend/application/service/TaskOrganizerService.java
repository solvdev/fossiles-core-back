package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OrganizerProductionOrderResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Organizador de Tareas: reemplaza la generación automática por un flujo manual.
 * El usuario arma tareas seleccionando cantidades (parciales o totales) de ítems
 * de OP con cantidad restante; el cupo base es 4h por tarea. Las líneas OPL
 * (VENTA_EN_LINEA / OPL-*) nunca cuentan contra el cupo (daySaleExtra automático).
 */
@Service
@RequiredArgsConstructor
public class TaskOrganizerService {

    private static final double EPSILON = 1e-9;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final TaskCodeGenerator taskCodeGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== LISTADO PARA EL ORGANIZADOR ====================

    /**
     * OPs activas del filtro (OPL / regulares / todas), con todos sus ítems no-cincho.
     * Incluye líneas ya totalmente asignadas (restante 0) para que OPL y demás se vean
     * aunque ya tengan tareas (p. ej. sin mesa); "Agregar" solo aplica si restante &gt; 0.
     *
     * @param type   OPL (solo venta en línea), REGULAR (todo lo demás) o ALL/null
     * @param search filtro por código de OP o nombre de cliente (contains, case-insensitive)
     */
    @Transactional(readOnly = true)
    public List<OrganizerProductionOrderResponse> getOrganizerOrders(String type, String search) {
        String normalizedType = type == null ? "ALL" : type.trim().toUpperCase(Locale.ROOT);
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<ProductionOrderEntity> orders = productionOrderRepository.findActiveOrders().stream()
                .filter(po -> !"COMPLETED".equals(po.getStatus())
                        && !"CANCELLED".equals(po.getStatus())
                        && !"DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim()))
                .filter(po -> !isCinchoOrderType(po.getOrderType()))
                .filter(po -> switch (normalizedType) {
                    case "OPL" -> isOnlineSaleOrder(po);
                    case "REGULAR" -> !isOnlineSaleOrder(po);
                    default -> true;
                })
                .filter(po -> normalizedSearch.isEmpty()
                        || String.valueOf(po.getCode()).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                        || String.valueOf(po.getCustomerName()).toLowerCase(Locale.ROOT).contains(normalizedSearch))
                .sorted(Comparator
                        .comparing(ProductionOrderEntity::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductionOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductionOrderEntity::getId))
                .toList();

        if (orders.isEmpty()) {
            return List.of();
        }

        Map<Long, List<ProductionOrderItemEntity>> itemsByOrder = new LinkedHashMap<>();
        List<Long> allItemIds = new ArrayList<>();
        for (ProductionOrderEntity po : orders) {
            List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId());
            itemsByOrder.put(po.getId(), items);
            items.forEach(i -> allItemIds.add(i.getId()));
        }

        Map<Long, Integer> assignedByItemId = taskItemRepository.assignedQuantityMap(allItemIds);
        Map<Long, List<Object[]>> assignmentRowsByItemId = taskItemRepository.assignmentRowsByItemId(allItemIds);

        List<OrganizerProductionOrderResponse> out = new ArrayList<>();
        for (ProductionOrderEntity po : orders) {
            List<OrganizerProductionOrderResponse.OrganizerItemResponse> itemRows = new ArrayList<>();
            for (ProductionOrderItemEntity item : itemsByOrder.getOrDefault(po.getId(), List.of())) {
                int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
                if (total <= 0) {
                    continue;
                }
                int assigned = assignedByItemId.getOrDefault(item.getId(), 0);
                int remaining = Math.max(0, total - assigned);

                ProductEntity product = item.getProductId() != null
                        ? productRepository.findById(item.getProductId()).orElse(null)
                        : null;
                // Cinchos (cinchoType explícito o nombre) van a la mesa cinchos, no al centro de
                // producción. El prefijo de código FOSS por sí solo NO cuenta (ver isCinchoLineForProduction).
                if (!isCinchoOrderType(po.getOrderType()) && CinchoProductUtils.isCinchoLineForProduction(product)) {
                    continue;
                }

                String colorName = null;
                if (item.getColorId() != null) {
                    colorName = colorRepository.findById(item.getColorId()).map(ColorEntity::getName).orElse(null);
                }

                List<OrganizerProductionOrderResponse.OrganizerItemAssignment> assignments = new ArrayList<>();
                for (Object[] row : assignmentRowsByItemId.getOrDefault(item.getId(), List.of())) {
                    Long taskId = (Long) row[1];
                    String taskCode = row[2] != null ? String.valueOf(row[2]) : null;
                    Integer desk = row[3] != null ? ((Number) row[3]).intValue() : null;
                    LocalDate scheduledDate = (LocalDate) row[4];
                    Integer qty = row[5] != null ? ((Number) row[5]).intValue() : null;
                    String status = row[6] != null ? String.valueOf(row[6]) : null;
                    Long taskItemId = (Long) row[7];
                    assignments.add(OrganizerProductionOrderResponse.OrganizerItemAssignment.builder()
                            .taskId(taskId)
                            .taskCode(taskCode)
                            .desk(desk)
                            .scheduledDate(scheduledDate)
                            .quantity(qty)
                            .status(status)
                            .taskItemId(taskItemId)
                            .build());
                }

                itemRows.add(OrganizerProductionOrderResponse.OrganizerItemResponse.builder()
                        .productionOrderItemId(item.getId())
                        .productId(item.getProductId())
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .totalQuantity(total)
                        .assignedQuantity(assigned)
                        .remainingQuantity(remaining)
                        .prdTimePerUnit(resolvePrdTimePerUnit(product))
                        .sizes(parseSizes(item.getSizesData()))
                        .observations(item.getObservations())
                        .assignments(assignments)
                        .build());
            }

            if (itemRows.isEmpty()) {
                continue;
            }

            out.add(OrganizerProductionOrderResponse.builder()
                    .id(po.getId())
                    .code(po.getCode())
                    .orderType(po.getOrderType())
                    .family(familyLabel(po.getOrderType(), po.getCode()))
                    .onlineSale(isOnlineSaleOrder(po))
                    .status(po.getStatus())
                    .customerName(po.getCustomerName())
                    .startDate(po.getStartDate())
                    .deliveryDate(po.getDeliveryDate())
                    .createdAt(po.getCreatedAt())
                    .items(itemRows)
                    .build());
        }
        return out;
    }

    // ==================== CREACIÓN MANUAL ====================

    /**
     * Crea una tarea desde el organizador. Bloquea los ítems de OP (lock pesimista,
     * ids ordenados) y revalida la cantidad restante dentro de la transacción para
     * evitar sobre-asignación concurrente.
     */
    @Transactional
    public TaskEntity createManualTask(CreateManualTaskRequest request)
            throws ResourceNotFoundException, BusinessException {

        if (request == null || request.getProductionOrderId() == null) {
            throw new BusinessException("Debe indicar la orden de producción base de la tarea.");
        }
        List<CreateManualTaskRequest.ManualTaskItemRequest> lines = request.getItems();
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("Debe agregar al menos un producto a la tarea.");
        }

        if (!ProductionPlanningConstants.isWorkday(request.getScheduledDate())) {
            throw new BusinessException("Solo se puede programar de lunes a viernes: "
                    + request.getScheduledDate() + " es fin de semana.");
        }

        ProductionOrderEntity headerOrder = productionOrderRepository.findById(request.getProductionOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", request.getProductionOrderId()));
        assertOrderReadyForTasks(headerOrder);

        List<Long> requestedItemIds = new ArrayList<>();
        for (CreateManualTaskRequest.ManualTaskItemRequest line : lines) {
            if (line.getProductionOrderItemId() == null) {
                throw new BusinessException("Hay una línea sin ítem de orden de producción.");
            }
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new BusinessException("Las cantidades deben ser mayores a cero.");
            }
            if (requestedItemIds.contains(line.getProductionOrderItemId())) {
                throw new BusinessException("El ítem " + line.getProductionOrderItemId()
                        + " aparece más de una vez en la tarea. Combine las cantidades en una sola línea.");
            }
            requestedItemIds.add(line.getProductionOrderItemId());
        }

        // Lock pesimista en orden ascendente (el query ya ordena por id) — evita deadlocks
        // y congela las cantidades mientras validamos el restante.
        List<Long> sortedIds = requestedItemIds.stream().sorted().toList();
        Map<Long, ProductionOrderItemEntity> lockedById = productionOrderItemRepository.findAllByIdForUpdate(sortedIds)
                .stream().collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i));
        for (Long itemId : requestedItemIds) {
            if (!lockedById.containsKey(itemId)) {
                throw new BusinessException("El ítem " + itemId + " ya no existe en la orden de producción.");
            }
        }

        Map<Long, Integer> assignedByItemId = taskItemRepository.assignedQuantityMap(requestedItemIds);
        Map<Long, ProductionOrderEntity> ordersById = new HashMap<>();
        ordersById.put(headerOrder.getId(), headerOrder);

        double baseHours = 0.0;
        double totalHours = 0.0;
        int totalQuantity = 0;
        List<TaskItemEntity> itemsToSave = new ArrayList<>();

        for (CreateManualTaskRequest.ManualTaskItemRequest line : lines) {
            ProductionOrderItemEntity item = lockedById.get(line.getProductionOrderItemId());

            ProductionOrderEntity itemOrder = ordersById.computeIfAbsent(item.getProductionOrderId(),
                    id -> productionOrderRepository.findById(id).orElse(null));
            if (itemOrder == null) {
                throw new BusinessException("El ítem " + item.getId() + " pertenece a una orden inexistente.");
            }

            boolean onlineSale = isOnlineSaleOrder(itemOrder);
            // OPL never consumes desk cupo — force daySaleExtra regardless of client flag.
            boolean extra = onlineSale || Boolean.TRUE.equals(line.getDaySaleExtra());

            if (Boolean.TRUE.equals(line.getDaySaleExtra()) && !onlineSale) {
                throw new BusinessException("Solo los productos de órdenes VENTA_EN_LINEA (OPL) "
                        + "pueden agregarse como extra sobre las 4 horas.");
            }
            if (!extra && !Objects.equals(itemOrder.getId(), headerOrder.getId())) {
                throw new BusinessException("El producto " + item.getId() + " pertenece a la orden "
                        + itemOrder.getCode() + ", distinta a la orden base de la tarea. "
                        + "Solo los extras OPL pueden mezclar órdenes.");
            }
            assertOrderReadyForTasks(itemOrder);

            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            if (CinchoProductUtils.isCinchoLineForProduction(product)) {
                throw new BusinessException("Los productos cincho se gestionan en la vista de Cinchos.");
            }

            int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            int assigned = assignedByItemId.getOrDefault(item.getId(), 0);
            int remaining = total - assigned;
            int qty = line.getQuantity();
            if (qty > remaining) {
                String productLabel = product != null ? product.getName() : ("ítem " + item.getId());
                throw new BusinessException("Cantidad no disponible para " + productLabel
                        + ": restante " + Math.max(remaining, 0) + " de " + total
                        + " (ya asignado " + assigned + ").");
            }

            double prdTimePerUnit = resolvePrdTimePerUnit(product);
            double lineHours = roundHours(qty * prdTimePerUnit);
            totalHours += lineHours;
            totalQuantity += qty;
            if (!extra) {
                baseHours += lineHours;
            }

            String colorName = null;
            if (item.getColorId() != null) {
                colorName = colorRepository.findById(item.getColorId()).map(ColorEntity::getName).orElse(null);
            }
            boolean requiresMaterials = product == null || !Boolean.FALSE.equals(product.getRequiresMaterials());

            itemsToSave.add(TaskItemEntity.builder()
                    .productionOrderItemId(item.getId())
                    .productId(item.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(item.getColorId())
                    .colorName(colorName)
                    .quantity(qty)
                    .estimatedHours(lineHours)
                    .observations(buildItemObservations(item, qty, total))
                    .leatherDelivered(false)
                    .leatherDeliveredAt(null)
                    .materialsDelivered(!requiresMaterials)
                    .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                    .daySaleExtra(extra)
                    .build());
        }

        // Pure OPL tasks (header OPL) never consume cupo even if a flag were missing.
        if (isOnlineSaleOrder(headerOrder)) {
            baseHours = 0.0;
        }
        if (baseHours > ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP + EPSILON) {
            throw new BusinessException(String.format(Locale.ROOT,
                    "La carga base de la tarea (%.2f h) excede el máximo de %.1f horas. "
                            + "Divida los productos en otra tarea (las OPL no cuentan contra el cupo).",
                    baseHours, ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP));
        }

        TaskItemEntity primary = itemsToSave.get(0);
        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .code(taskCodeGenerator.generateTaskCode())
                .productionOrderId(headerOrder.getId())
                .productionOrderCode(headerOrder.getCode())
                .productionOrderItemId(primary.getProductionOrderItemId())
                .productId(primary.getProductId())
                .productCode(primary.getProductCode())
                .productName(primary.getProductName())
                .colorId(primary.getColorId())
                .colorName(primary.getColorName())
                .quantity(totalQuantity)
                .estimatedHours(roundHours(totalHours))
                .deliveryDate(headerOrder.getDeliveryDate())
                .desk(request.getDesk())
                .scheduledDate(request.getScheduledDate())
                .priority(5)
                .status("PENDING")
                .observations(request.getObservations())
                .build());

        for (TaskItemEntity item : itemsToSave) {
            item.setTaskId(task.getId());
            taskItemRepository.save(item);
        }

        // Igual que la generación clásica: al tener tareas, las OP involucradas pasan a IN_PROGRESS.
        for (ProductionOrderEntity order : ordersById.values()) {
            if (order != null && "PENDING".equals(order.getStatus())) {
                order.setStatus("IN_PROGRESS");
                productionOrderRepository.save(order);
            }
        }

        return task;
    }

    /**
     * "Limpiar mesas": libera mesa y fecha de todas las tareas PENDING para reorganizar
     * desde cero (arrastrar de nuevo en el tablero). No toca tareas ya iniciadas/terminadas.
     *
     * @return cantidad de tareas liberadas
     */
    @Transactional
    public int clearAllPendingDeskAssignments() {
        return taskRepository.clearAllPendingDeskAssignments();
    }

    /**
     * "Reiniciar tareas del día": libera solo la mesa (conserva la fecha) de las tareas
     * PENDING programadas ese día, para reorganizar el tablero de ese día sin afectar
     * la planificación de otros días.
     *
     * @return cantidad de tareas liberadas
     */
    @Transactional
    public int clearPendingDeskAssignmentsForDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date es requerida");
        }
        return taskRepository.clearPendingDeskAssignmentsForDate(date);
    }

    // ==================== HELPERS ====================

    private String buildItemObservations(ProductionOrderItemEntity item, int qty, int total) {
        String base = item.getObservations() != null ? item.getObservations().trim() : "";
        if (qty >= total) {
            return base.isEmpty() ? null : base;
        }
        StringBuilder note = new StringBuilder();
        note.append("Parcial ").append(qty).append("/").append(total);
        Map<String, Integer> sizes = parseSizes(item.getSizesData());
        if (sizes != null && !sizes.isEmpty()) {
            String sizesText = sizes.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(", "));
            note.append(" — tallas de la OP: ").append(sizesText).append(" (desglose a criterio de mesa)");
        }
        String combined = base.isEmpty() ? note.toString() : base + " | " + note;
        // task_item.observations es VARCHAR(500)
        return combined.length() > 500 ? combined.substring(0, 500) : combined;
    }

    private Map<String, Integer> parseSizes(String sizesData) {
        if (sizesData == null || sizesData.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(sizesData, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double resolvePrdTimePerUnit(ProductEntity product) {
        return (product != null && product.getPrdTime() != null && product.getPrdTime() > 0)
                ? product.getPrdTime()
                : ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
    }

    private static boolean isOnlineSaleOrder(ProductionOrderEntity po) {
        if (po == null) {
            return false;
        }
        return ProductionPlanningConstants.isOnlineSaleOrder(po.getOrderType(), po.getCode());
    }

    private static boolean isCinchoOrderType(String orderType) {
        if (orderType == null || orderType.isBlank()) {
            return false;
        }
        String t = orderType.trim().toUpperCase(Locale.ROOT);
        return "CINCHOS".equals(t) || "CINCHOS_FOSSILES".equals(t) || "CINCHOS_MARCAS".equals(t);
    }

    private static void assertOrderReadyForTasks(ProductionOrderEntity po) throws BusinessException {
        if (po == null) {
            return;
        }
        if ("DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim())) {
            String code = po.getCode() != null ? po.getCode() : "OPI";
            throw new BusinessException(
                    "La orden " + code + " está en borrador. Contabilidad debe autorizar la producción "
                            + "antes de crear tareas.");
        }
        if (isCinchoOrderType(po.getOrderType())) {
            throw new BusinessException(
                    "Las órdenes de tipo cinchos se gestionan en la vista de Cinchos, no en el centro de producción.");
        }
    }

    /** OPL | OPV | OPK | OPI | OPCK | OPD, o prefijo del código como fallback. */
    private static String familyLabel(String orderType, String code) {
        String ot = orderType == null ? "" : orderType.trim().toUpperCase(Locale.ROOT);
        switch (ot) {
            case "VENTA_EN_LINEA": return "OPL";
            case "NORMAL": return "OPK";
            case "MARCAS", "OPV": return "OPV";
            case "INTERNA": return "OPI";
            case "CLIENTE_KIOSKO": return "OPCK";
            case "DISTRIBUTION": return "OPD";
            default:
                String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
                int dash = c.indexOf('-');
                return dash > 0 ? c.substring(0, dash) : (c.isEmpty() ? null : c);
        }
    }

    private static double roundHours(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
