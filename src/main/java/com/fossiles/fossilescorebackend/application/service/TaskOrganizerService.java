package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OrganizerOrderPageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OrganizerProductionOrderResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderPlanPriority;
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

    /** Valores que acepta el filtro por tipo. ALL y REGULAR se conservan por compatibilidad. */
    private static final Set<String> TIPOS_VALIDOS =
            Set.of("ALL", "REGULAR", "OPL", "OPK", "OPV", "OPI", "OPCK", "OPD");

    @Transactional(readOnly = true)
    public List<OrganizerProductionOrderResponse> getOrganizerOrders(String type, String search)
            throws BusinessException {
        return getOrganizerOrders(type, search, 0, Integer.MAX_VALUE).getContent();
    }

    /**
     * Listado de órdenes con trabajo pendiente, filtrable por familia y paginado.
     *
     * <p>La página se recorta <b>antes</b> de cargar ítems, productos y colores: antes se
     * hacía una consulta por orden, otra por producto y otra por color sobre el catálogo
     * entero, aunque en pantalla solo cupieran treinta filas.
     */
    @Transactional(readOnly = true)
    public OrganizerOrderPageResponse getOrganizerOrders(String type, String search, int page, int size)
            throws BusinessException {
        String normalizedType = type == null ? "ALL" : type.trim().toUpperCase(Locale.ROOT);
        if (!TIPOS_VALIDOS.contains(normalizedType)) {
            // Antes cualquier valor desconocido caía en `default -> true` y devolvía el
            // catálogo completo: un error de tecleo se veía como "no hay filtro".
            throw new BusinessException("Tipo de orden no válido: " + type
                    + ". Valores aceptados: " + String.join(", ", TIPOS_VALIDOS));
        }
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int pageIndex = Math.max(page, 0);

        // El auxiliar teclea el código de su boleta, que es el de la TAREA. Se resuelven
        // aquí, de una sola consulta, las OP que tienen alguna tarea con ese código.
        Set<Long> ordersMatchingTaskCode = normalizedSearch.isEmpty()
                ? Set.of()
                : new HashSet<>(taskRepository.findProductionOrderIdsByTaskCodeLike(normalizedSearch));

        List<ProductionOrderEntity> orders = productionOrderRepository.findActiveOrders().stream()
                .filter(po -> !"COMPLETED".equals(po.getStatus())
                        && !"CANCELLED".equals(po.getStatus())
                        && !"DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim()))
                .filter(po -> !isCinchoOrderType(po.getOrderType()))
                .filter(po -> switch (normalizedType) {
                    case "ALL" -> true;
                    case "OPL" -> isOnlineSaleOrder(po);
                    case "REGULAR" -> !isOnlineSaleOrder(po);
                    // Las familias reales salen del mismo sitio que la etiqueta que se le
                    // pinta a la fila, para que filtro y badge no puedan desincronizarse.
                    default -> normalizedType.equals(familyLabel(po.getOrderType(), po.getCode()));
                })
                .filter(po -> normalizedSearch.isEmpty()
                        || String.valueOf(po.getCode()).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                        || String.valueOf(po.getCustomerName()).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                        || ordersMatchingTaskCode.contains(po.getId()))
                // Mismo orden que aplica el planificador: prioridad, luego FIFO. Mostrar
                // otro orden aquí, que es donde el auxiliar arma la cola, haría que lo que
                // ve no fuera lo que se va a ejecutar.
                .sorted(ProductionOrderPlanPriority.comparator())
                .toList();

        long totalElements = orders.size();
        int totalPages = (int) Math.ceil(totalElements / (double) pageSize);
        int desde = Math.min(pageIndex * pageSize, orders.size());
        int hasta = Math.min(desde + pageSize, orders.size());
        List<ProductionOrderEntity> pagina = orders.subList(desde, hasta);

        if (pagina.isEmpty()) {
            return OrganizerOrderPageResponse.of(List.of(), totalElements, totalPages, pageSize, pageIndex);
        }

        // Los tres N+1 a lote, y solo sobre la página: antes eran una consulta por orden,
        // otra por producto y otra por color, sobre el catálogo entero.
        List<Long> pageOrderIds = pagina.stream().map(ProductionOrderEntity::getId).toList();
        Map<Long, List<ProductionOrderItemEntity>> itemsByOrder =
                productionOrderItemRepository.findByProductionOrderIdIn(pageOrderIds).stream()
                        .collect(Collectors.groupingBy(ProductionOrderItemEntity::getProductionOrderId));

        List<Long> allItemIds = itemsByOrder.values().stream()
                .flatMap(List::stream)
                .map(ProductionOrderItemEntity::getId)
                .toList();

        Set<Long> productIds = itemsByOrder.values().stream().flatMap(List::stream)
                .map(ProductionOrderItemEntity::getProductId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> productsById = productIds.isEmpty() ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, p -> p));

        Set<Long> colorIds = itemsByOrder.values().stream().flatMap(List::stream)
                .map(ProductionOrderItemEntity::getColorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> colorNameById = colorIds.isEmpty() ? Map.of()
                : colorRepository.findAllById(colorIds).stream()
                        .collect(Collectors.toMap(ColorEntity::getId, ColorEntity::getName));

        Map<Long, Integer> assignedByItemId = taskItemRepository.assignedQuantityMap(allItemIds);
        Map<Long, List<Object[]>> assignmentRowsByItemId = taskItemRepository.assignmentRowsByItemId(allItemIds);

        List<OrganizerProductionOrderResponse> out = new ArrayList<>();
        for (ProductionOrderEntity po : pagina) {
            List<OrganizerProductionOrderResponse.OrganizerItemResponse> itemRows = new ArrayList<>();
            for (ProductionOrderItemEntity item : itemsByOrder.getOrDefault(po.getId(), List.of())) {
                int total = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
                if (total <= 0) {
                    continue;
                }
                int assigned = assignedByItemId.getOrDefault(item.getId(), 0);
                int remaining = Math.max(0, total - assigned);

                ProductEntity product = item.getProductId() != null
                        ? productsById.get(item.getProductId())
                        : null;
                // Cinchos (cinchoType explícito o nombre) van a la mesa cinchos, no al centro de
                // producción. El prefijo de código FOSS por sí solo NO cuenta (ver isCinchoLineForProduction).
                if (!isCinchoOrderType(po.getOrderType()) && CinchoProductUtils.isCinchoLineForProduction(product)) {
                    continue;
                }

                String colorName = null;
                if (item.getColorId() != null) {
                    colorName = colorNameById.get(item.getColorId());
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
        return OrganizerOrderPageResponse.of(out, totalElements, totalPages, pageSize, pageIndex);
    }

    // ==================== CREACIÓN MANUAL ====================

    /**
     * Crea una tarea desde el organizador. Bloquea los ítems de OP (lock pesimista,
     * ids ordenados) y revalida la cantidad restante dentro de la transacción para
     * evitar sobre-asignación concurrente.
     */
    public enum CreateMode { MANUAL, AUTO_CENTRO, AUTO_CINCHO }

    @Transactional
    public TaskEntity createManualTask(CreateManualTaskRequest request)
            throws ResourceNotFoundException, BusinessException {
        return createTask(request, CreateMode.MANUAL);
    }

    @Transactional
    public TaskEntity createAutoCentroTask(CreateManualTaskRequest request)
            throws ResourceNotFoundException, BusinessException {
        return createTask(request, CreateMode.AUTO_CENTRO);
    }

    @Transactional
    public TaskEntity createAutoCinchoTask(CreateManualTaskRequest request)
            throws ResourceNotFoundException, BusinessException {
        return createTask(request, CreateMode.AUTO_CINCHO);
    }

    @Transactional
    public TaskEntity createTask(CreateManualTaskRequest request, CreateMode mode)
            throws ResourceNotFoundException, BusinessException {
        CreateMode createMode = mode == null ? CreateMode.MANUAL : mode;

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
        if (createMode == CreateMode.AUTO_CINCHO) {
            assertNotDraft(headerOrder);
        } else {
            assertOrderReadyForTasks(headerOrder);
        }

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
            if (createMode == CreateMode.AUTO_CINCHO) {
                assertNotDraft(itemOrder);
            } else {
                assertOrderReadyForTasks(itemOrder);
            }

            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            boolean cinchoLine = CinchoProductUtils.isCinchoLineForProduction(product);
            if (createMode == CreateMode.AUTO_CINCHO && !cinchoLine) {
                throw new BusinessException("La tarea de cinchos solo admite líneas cincho.");
            }
            if (createMode != CreateMode.AUTO_CINCHO && cinchoLine) {
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
            boolean cinchoReady = createMode == CreateMode.AUTO_CINCHO;

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
                    .leatherDelivered(cinchoReady)
                    .leatherDeliveredAt(cinchoReady ? LocalDateTime.now() : null)
                    .materialsDelivered(!requiresMaterials)
                    .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                    .daySaleExtra(extra)
                    .build());
        }

        // Pure OPL tasks (header OPL) never consume cupo even if a flag were missing.
        if (isOnlineSaleOrder(headerOrder)) {
            baseHours = 0.0;
        }
        if (createMode == CreateMode.MANUAL
                && baseHours > ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP + EPSILON) {
            throw new BusinessException(String.format(Locale.ROOT,
                    "La carga base de la tarea (%.2f h) excede el máximo de %.1f horas. "
                            + "Divida los productos en otra tarea (las OPL no cuentan contra el cupo).",
                    baseHours, ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP));
        }

        boolean cinchoReady = createMode == CreateMode.AUTO_CINCHO;
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
                .desk(cinchoReady ? null : request.getDesk())
                .scheduledDate(request.getScheduledDate())
                .priority(5)
                .status("PENDING")
                .dieCutReady(cinchoReady)
                .leatherDelivered(cinchoReady)
                .leatherDeliveredAt(cinchoReady ? LocalDateTime.now() : null)
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

    private static void assertNotDraft(ProductionOrderEntity po) throws BusinessException {
        if (po == null) {
            return;
        }
        if ("DRAFT".equalsIgnoreCase(String.valueOf(po.getStatus()).trim())) {
            String code = po.getCode() != null ? po.getCode() : "OPI";
            throw new BusinessException(
                    "La orden " + code + " está en borrador. Contabilidad debe autorizar la producción "
                            + "antes de crear tareas.");
        }
    }

    private static void assertOrderReadyForTasks(ProductionOrderEntity po) throws BusinessException {
        assertNotDraft(po);
        if (po != null && isCinchoOrderType(po.getOrderType())) {
            throw new BusinessException(
                    "Las órdenes de tipo cinchos se gestionan en la vista de Cinchos, no en el centro de producción.");
        }
    }

    /**
     * OPL | OPV | OPK | OPI | OPCK | OPD | OPC, o prefijo del código como fallback.
     *
     * <p>Delega en {@link ProductionPlanningConstants#orderFamilyLabel}, que pasa a ser la
     * única implementación. Único cambio de comportamiento respecto a la copia que vivía
     * aquí: los tres tipos de cincho se resuelven por tipo y no solo por prefijo del código.
     * Al Organizador no le afecta —su listado ya excluye cinchos antes de llegar aquí— pero
     * deja de haber dos respuestas posibles para la misma orden.
     */
    private static String familyLabel(String orderType, String code) {
        return ProductionPlanningConstants.orderFamilyLabel(orderType, code);
    }

    private static double roundHours(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
