package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.LineProgress;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.OrderProgress;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.Stages;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.TaskLine;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.TaskProgress;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionProgressRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Avance de las OP activas en piezas y tareas, para el Dashboard de Producción.
 * <p>
 * Cada pieza pedida de una línea cae en una sola etapa, según el estado de la tarea que la
 * lleva (sin contar canceladas): PENDING = por iniciar, IN_PROGRESS = en mesa,
 * AWAITING_WAREHOUSE = en bodega/QA, COMPLETED = terminada. Lo pedido que no está en ninguna
 * tarea queda «sin tarea». Las piezas en bodega/QA que ya se recibieron
 * ({@code warehouseReceivedQty}) pasan a terminadas. Si las tareas suman más que lo pedido,
 * se recorta empezando por la etapa más atrasada, para que las etapas sumen lo pedido.
 * Los extras OPL ({@code daySaleExtra}) cuentan en la línea a la que apuntan.
 * <p>
 * Seis consultas en total, sin importar cuántas órdenes haya.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderProgressService {

    static final List<String> ACTIVE_STATUSES = List.of("PENDING", "IN_PROGRESS", "IN_QA");

    private static final String PENDING = "PENDING";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String AWAITING = ProductionTaskLifecycleService.STATUS_AWAITING_WAREHOUSE;
    private static final String COMPLETED = "COMPLETED";

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionProgressRepository productionProgressRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;

    /** Piezas de una tarea sobre una línea de OP (un task_item, o la tarea vieja sin task_item). */
    private record Piece(TaskEntity task, Long lineId, int qty, boolean extra, TaskItemEntity item) {}

    @Transactional(readOnly = true)
    public ProductionOrderProgressResponse report() {
        return report(GuatemalaDateTime.today());
    }

    ProductionOrderProgressResponse report(LocalDate today) {
        List<ProductionOrderEntity> orders = productionOrderRepository.findByStatusIn(ACTIVE_STATUSES);
        Set<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).collect(Collectors.toSet());
        List<ProductionOrderItemEntity> lines = orderIds.isEmpty()
                ? List.of()
                : productionOrderItemRepository.findByProductionOrderIdIn(orderIds);
        Map<Long, ProductionOrderItemEntity> lineById = byId(lines, ProductionOrderItemEntity::getId);
        Map<Long, List<ProductionOrderItemEntity>> linesByOrder = lines.stream()
                .collect(Collectors.groupingBy(ProductionOrderItemEntity::getProductionOrderId));

        List<TaskEntity> tasks = orderIds.isEmpty()
                ? List.of()
                : productionProgressRepository.findActiveTasksForOrders(orderIds);
        List<Long> taskIds = tasks.stream().map(TaskEntity::getId).toList();
        List<TaskItemEntity> taskItems = taskIds.isEmpty() ? List.of() : taskItemRepository.findByTaskIdIn(taskIds);
        Map<Long, List<TaskItemEntity>> itemsByTask = taskItems.stream()
                .collect(Collectors.groupingBy(TaskItemEntity::getTaskId));

        Set<Long> productIds = Stream.of(
                        lines.stream().map(ProductionOrderItemEntity::getProductId),
                        taskItems.stream().map(TaskItemEntity::getProductId),
                        tasks.stream().map(TaskEntity::getProductId))
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> products = productIds.isEmpty() ? Map.of()
                : byId(productRepository.findAllById(productIds), ProductEntity::getId);
        Set<Long> colorIds = lines.stream().map(ProductionOrderItemEntity::getColorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ColorEntity> colors = colorIds.isEmpty() ? Map.of()
                : byId(colorRepository.findAllById(colorIds), ColorEntity::getId);

        // Piezas de cada tarea, y de ahí las de cada línea activa.
        Map<Long, List<Piece>> piecesByTask = new LinkedHashMap<>();
        Map<Long, List<Piece>> piecesByLine = new HashMap<>();
        for (TaskEntity task : tasks) {
            List<Piece> pieces = new ArrayList<>();
            List<TaskItemEntity> items = itemsByTask.getOrDefault(task.getId(), List.of());
            if (items.isEmpty()) {
                if (task.getProductionOrderItemId() != null) {
                    pieces.add(new Piece(task, task.getProductionOrderItemId(), nz(task.getQuantity()), false, null));
                }
            } else {
                for (TaskItemEntity ti : items) {
                    pieces.add(new Piece(task, ti.getProductionOrderItemId(), nz(ti.getQuantity()),
                            Boolean.TRUE.equals(ti.getDaySaleExtra()), ti));
                }
            }
            piecesByTask.put(task.getId(), pieces);
            for (Piece p : pieces) {
                if (p.lineId() != null && lineById.containsKey(p.lineId())) {
                    piecesByLine.computeIfAbsent(p.lineId(), k -> new ArrayList<>()).add(p);
                }
            }
        }

        List<OrderProgress> orderRows = new ArrayList<>();
        StageAcc grand = new StageAcc();
        Set<Long> tasksInProgressAll = new HashSet<>();
        int lateOrders = 0;
        int ordersWorkingNow = 0;

        for (ProductionOrderEntity order : orders) {
            List<ProductionOrderItemEntity> orderLines = linesByOrder.getOrDefault(order.getId(), List.of());
            if (orderLines.isEmpty()) {
                continue;
            }
            StageAcc orderAcc = new StageAcc();
            int received = 0;
            List<LineProgress> lineRows = new ArrayList<>();
            // Tareas de esta OP con sus piezas sobre las líneas de esta OP.
            Map<Long, List<Piece>> orderPiecesByTask = new LinkedHashMap<>();

            for (ProductionOrderItemEntity line : orderLines) {
                List<Piece> pieces = piecesByLine.getOrDefault(line.getId(), List.of());
                int[] raw = new int[4]; // por iniciar, en mesa, bodega/QA, terminadas
                int extra = 0;
                for (Piece p : pieces) {
                    int idx = stageIndex(p.task().getStatus());
                    if (idx >= 0) {
                        raw[idx] += p.qty();
                    }
                    if (p.extra()) {
                        extra += p.qty();
                    }
                    orderPiecesByTask.computeIfAbsent(p.task().getId(), k -> new ArrayList<>()).add(p);
                }
                int units = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(line);
                int lineReceived = Math.max(nz(line.getWarehouseReceivedQty()), 0);
                StageAcc lineAcc = StageAcc.of(units, raw, lineReceived);
                orderAcc.add(lineAcc);
                received += lineReceived;

                ProductEntity product = products.get(line.getProductId());
                ColorEntity color = colors.get(line.getColorId());
                lineRows.add(LineProgress.builder()
                        .itemId(line.getId())
                        .productId(line.getProductId())
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .colorId(line.getColorId())
                        .colorName(color != null && color.getName() != null ? color.getName().trim()
                                : (line.getColorId() != null ? "#" + line.getColorId() : null))
                        .brandName(line.getBrandName())
                        .sizesData(line.getSizesData())
                        .stages(lineAcc.toStages())
                        .receivedUnits(lineReceived)
                        .extraUnits(extra)
                        .overAssignedUnits(lineAcc.over)
                        .build());
            }
            lineRows.sort(Comparator.comparing(LineProgress::getProductCode, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LineProgress::getColorName, Comparator.nullsLast(Comparator.naturalOrder())));

            List<TaskProgress> taskRows = new ArrayList<>();
            int inProgressTasks = 0;
            for (Map.Entry<Long, List<Piece>> e : orderPiecesByTask.entrySet()) {
                List<Piece> mine = e.getValue();
                TaskEntity task = mine.get(0).task();
                List<Piece> all = piecesByTask.getOrDefault(task.getId(), mine);
                TaskProgress row = toTaskRow(order, task, mine, all, itemsByTask.getOrDefault(task.getId(), List.of()),
                        products);
                taskRows.add(row);
                if (IN_PROGRESS.equals(task.getStatus())) {
                    inProgressTasks++;
                    tasksInProgressAll.add(task.getId());
                }
            }
            taskRows.sort(Comparator.comparingInt((TaskProgress t) -> taskRank(t.getStatus()))
                    .thenComparing(TaskProgress::getScheduledDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TaskProgress::getCode, Comparator.nullsLast(Comparator.naturalOrder())));

            Integer days = order.getDeliveryDate() != null
                    ? (int) ChronoUnit.DAYS.between(today, order.getDeliveryDate())
                    : null;
            boolean late = days != null && days < 0;
            if (late) {
                lateOrders++;
            }
            if (inProgressTasks > 0) {
                ordersWorkingNow++;
            }
            grand.add(orderAcc);

            orderRows.add(OrderProgress.builder()
                    .orderId(order.getId())
                    .code(order.getCode())
                    .orderType(order.getOrderType())
                    .status(order.getStatus())
                    .customerName(order.getCustomerName() != null && !order.getCustomerName().isBlank()
                            ? order.getCustomerName() : order.getSellerName())
                    .sellerName(order.getSellerName())
                    .startDate(order.getStartDate())
                    .deliveryDate(order.getDeliveryDate())
                    .daysToDelivery(days)
                    .late(late)
                    .stages(orderAcc.toStages())
                    .receivedUnits(received)
                    .overAssignedUnits(orderAcc.over)
                    .taskCount(taskRows.size())
                    .tasksInProgress(inProgressTasks)
                    .lines(lineRows)
                    .tasks(taskRows)
                    .build());
        }

        orderRows.sort(Comparator.comparing(OrderProgress::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OrderProgress::getCode, Comparator.nullsLast(Comparator.naturalOrder())));

        return ProductionOrderProgressResponse.builder()
                .generatedAt(GuatemalaDateTime.now())
                .today(today)
                .totals(ProductionOrderProgressResponse.Totals.builder()
                        .orders(orderRows.size())
                        .lateOrders(lateOrders)
                        .ordersWorkingNow(ordersWorkingNow)
                        .tasksInProgress(tasksInProgressAll.size())
                        .stages(grand.toStages())
                        .build())
                .orders(orderRows)
                .build();
    }

    private TaskProgress toTaskRow(ProductionOrderEntity order, TaskEntity task, List<Piece> mine, List<Piece> all,
                                   List<TaskItemEntity> items, Map<Long, ProductEntity> products) {
        boolean requiresMaterials = requiresMaterials(task, items, products);
        boolean materialsDelivered = materialsDelivered(task, items, products);
        boolean leatherDelivered = Boolean.TRUE.equals(task.getLeatherDelivered())
                || (!items.isEmpty() && items.stream().allMatch(i -> Boolean.TRUE.equals(i.getLeatherDelivered())));
        String obs = task.getObservations();

        List<TaskLine> lines = mine.stream().map(p -> {
            TaskItemEntity ti = p.item();
            Long productId = ti != null ? ti.getProductId() : task.getProductId();
            ProductEntity product = products.get(productId);
            String code = ti != null && ti.getProductCode() != null ? ti.getProductCode()
                    : (product != null ? product.getCode() : task.getProductCode());
            String name = ti != null && ti.getProductName() != null ? ti.getProductName()
                    : (product != null ? product.getName() : task.getProductName());
            String color = ti != null ? ti.getColorName() : task.getColorName();
            return TaskLine.builder()
                    .itemId(p.lineId())
                    .productCode(code)
                    .productName(name)
                    .colorName(color)
                    .quantity(p.qty())
                    .extra(p.extra())
                    .leatherDelivered(ti != null ? Boolean.TRUE.equals(ti.getLeatherDelivered()) : leatherDelivered)
                    .materialsDelivered(ti != null ? Boolean.TRUE.equals(ti.getMaterialsDelivered()) : materialsDelivered)
                    .dieCutReady(ti != null ? Boolean.TRUE.equals(ti.getDieCutReady()) : Boolean.TRUE.equals(task.getDieCutReady()))
                    .build();
        }).toList();

        return TaskProgress.builder()
                .taskId(task.getId())
                .code(task.getCode())
                .status(task.getStatus())
                .workflowStatus(workflowStatus(task, leatherDelivered, requiresMaterials, materialsDelivered))
                .baseOrderCode(Objects.equals(task.getProductionOrderId(), order.getId()) ? null : task.getProductionOrderCode())
                .extra(mine.stream().allMatch(Piece::extra))
                .materialsTask(obs != null && obs.toLowerCase().contains("materiales cincho"))
                .desk(task.getDesk())
                .workedDesk(task.getWorkedDesk())
                .units(mine.stream().mapToInt(Piece::qty).sum())
                .taskUnits(all.stream().mapToInt(Piece::qty).sum())
                .scheduledDate(task.getScheduledDate())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .pausedAt(task.getPausedAt())
                .paused(IN_PROGRESS.equals(task.getStatus()) && task.getPausedAt() != null)
                .delayed(Boolean.TRUE.equals(task.getDelayed()))
                .leatherDelivered(leatherDelivered)
                .materialsDelivered(materialsDelivered)
                .requiresMaterials(requiresMaterials)
                .dieCutReady(Boolean.TRUE.equals(task.getDieCutReady()))
                .lines(lines)
                .build();
    }

    /**
     * Paso del flujo, con la misma regla que el tablero (TaskController.getWorkflowStatus), pero
     * mirando primero el estado: una tarea ya iniciada o terminada no vuelve a «falta cuero».
     */
    static String workflowStatus(TaskEntity task, boolean leatherDelivered, boolean requiresMaterials,
                                 boolean materialsDelivered) {
        String status = task.getStatus();
        if (COMPLETED.equals(status)) return "COMPLETED";
        if (AWAITING.equals(status)) return "PENDING_WAREHOUSE_RECEIPT";
        if (IN_PROGRESS.equals(status)) return "IN_PRODUCTION";
        if (!leatherDelivered) return "PENDING_LEATHER";
        if (!Boolean.TRUE.equals(task.getDieCutReady())) return "PENDING_DIE_CUT";
        boolean onTable = task.getDesk() != null
                || task.getScheduledDate() != null
                || (task.getStartTime() != null && !task.getStartTime().isBlank());
        if (!onTable) return "PENDING_TABLE_ENTRY";
        if (requiresMaterials && !materialsDelivered) return "PENDING_MATERIAL_DELIVERY";
        return "READY_TO_START";
    }

    private static boolean productRequiresMaterials(ProductEntity product) {
        return product == null || !Boolean.FALSE.equals(product.getRequiresMaterials());
    }

    private static boolean requiresMaterials(TaskEntity task, List<TaskItemEntity> items, Map<Long, ProductEntity> products) {
        if (!items.isEmpty()) {
            return items.stream().anyMatch(i -> i.getProductId() == null || productRequiresMaterials(products.get(i.getProductId())));
        }
        return task.getProductId() == null || productRequiresMaterials(products.get(task.getProductId()));
    }

    private static boolean materialsDelivered(TaskEntity task, List<TaskItemEntity> items, Map<Long, ProductEntity> products) {
        if (items.isEmpty()) {
            return Boolean.TRUE.equals(task.getMaterialsDelivered())
                    || !requiresMaterials(task, items, products);
        }
        return items.stream()
                .filter(i -> i.getProductId() == null || productRequiresMaterials(products.get(i.getProductId())))
                .allMatch(i -> Boolean.TRUE.equals(i.getMaterialsDelivered()));
    }

    /** 0 por iniciar, 1 en mesa, 2 bodega/QA, 3 terminada; -1 si no cuenta. */
    private static int stageIndex(String status) {
        if (PENDING.equals(status)) return 0;
        if (IN_PROGRESS.equals(status)) return 1;
        if (AWAITING.equals(status)) return 2;
        if (COMPLETED.equals(status)) return 3;
        return -1;
    }

    private static int taskRank(String status) {
        if (IN_PROGRESS.equals(status)) return 0;
        if (PENDING.equals(status)) return 1;
        if (AWAITING.equals(status)) return 2;
        if (COMPLETED.equals(status)) return 3;
        return 4;
    }

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> getter) {
        return rows.stream().filter(r -> getter.apply(r) != null)
                .collect(Collectors.toMap(getter, r -> r, (a, b) -> a));
    }

    /** Piezas por etapa; sus etapas siempre suman units. */
    private static final class StageAcc {
        int units;
        int noTask;
        int pending;
        int inProgress;
        int warehouse;
        int done;
        int over;

        /**
         * raw = piezas en tareas por estado [por iniciar, en mesa, bodega/QA, terminadas].
         * Lo ya recibido en bodega pasa de bodega/QA a terminadas. Luego se reparte lo pedido
         * de la etapa más avanzada a la más atrasada; lo que sobra es «sin tarea».
         */
        static StageAcc of(int units, int[] raw, int received) {
            int pending = Math.max(raw[0], 0);
            int inProgress = Math.max(raw[1], 0);
            int warehouse = Math.max(raw[2], 0);
            int done = Math.max(raw[3], 0);
            int receivedMove = Math.min(warehouse, Math.max(0, received - done));
            warehouse -= receivedMove;
            done += receivedMove;
            int assigned = pending + inProgress + warehouse + done;

            StageAcc acc = new StageAcc();
            acc.units = units;
            int left = units;
            acc.done = Math.min(done, left);
            left -= acc.done;
            acc.warehouse = Math.min(warehouse, left);
            left -= acc.warehouse;
            acc.inProgress = Math.min(inProgress, left);
            left -= acc.inProgress;
            acc.pending = Math.min(pending, left);
            left -= acc.pending;
            acc.noTask = left;
            acc.over = Math.max(0, assigned - (units - left));
            return acc;
        }

        void add(StageAcc o) {
            units += o.units;
            noTask += o.noTask;
            pending += o.pending;
            inProgress += o.inProgress;
            warehouse += o.warehouse;
            done += o.done;
            over += o.over;
        }

        Stages toStages() {
            return Stages.builder()
                    .units(units)
                    .noTask(noTask)
                    .pending(pending)
                    .inProgress(inProgress)
                    .warehouse(warehouse)
                    .done(done)
                    .build();
        }
    }
}
