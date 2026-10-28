package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Avance de las OP activas (pendientes, en proceso, en QA) en piezas, por etapa:
 * sin tarea → por iniciar (tarea pendiente) → en mesa (tarea iniciada) → en bodega/QA
 * (tarea terminada en mesa esperando recepción) → terminadas. Las etapas de cada línea
 * suman exactamente las piezas pedidas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderProgressResponse {
    private LocalDateTime generatedAt;
    private LocalDate today;
    private Totals totals;
    private List<OrderProgress> orders;

    /** Piezas por etapa. units = noTask + pending + inProgress + warehouse + done. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stages {
        private Integer units;
        private Integer noTask;
        private Integer pending;
        private Integer inProgress;
        private Integer warehouse;
        private Integer done;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private Integer orders;
        private Integer lateOrders;
        private Integer ordersWorkingNow;
        private Integer tasksInProgress;
        private Stages stages;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderProgress {
        private Long orderId;
        private String code;
        private String orderType;
        private String status;
        private String customerName;
        private String sellerName;
        private LocalDate startDate;
        private LocalDate deliveryDate;
        /** Días de hoy a la entrega (negativo = atrasada). Null si no tiene fecha. */
        private Integer daysToDelivery;
        private Boolean late;
        private Stages stages;
        /** Piezas ya recibidas en bodega (suma de warehouseReceivedQty de las líneas). */
        private Integer receivedUnits;
        /** Piezas asignadas a tareas por encima de lo pedido (no entran en las etapas). */
        private Integer overAssignedUnits;
        private Integer taskCount;
        private Integer tasksInProgress;
        private List<LineProgress> lines;
        private List<TaskProgress> tasks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineProgress {
        private Long itemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String brandName;
        private String sizesData;
        private Stages stages;
        private Integer receivedUnits;
        /** Piezas de esta línea que van como extra OPL en tareas de otra OP. */
        private Integer extraUnits;
        private Integer overAssignedUnits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskProgress {
        private Long taskId;
        private String code;
        private String status;
        private String workflowStatus;
        /** OP base de la tarea si no es esta (extra OPL o tarea consolidada). */
        private String baseOrderCode;
        private Boolean extra;
        private Boolean materialsTask;
        private Integer desk;
        private Integer workedDesk;
        /** Piezas de esta OP en la tarea (las de otras OP no se cuentan aquí). */
        private Integer units;
        /** Piezas totales de la tarea, todas las OP. */
        private Integer taskUnits;
        private LocalDate scheduledDate;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private LocalDateTime pausedAt;
        private Boolean paused;
        private Boolean delayed;
        private Boolean leatherDelivered;
        private Boolean materialsDelivered;
        private Boolean requiresMaterials;
        private Boolean dieCutReady;
        private List<TaskLine> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskLine {
        private Long itemId;
        private String productCode;
        private String productName;
        private String colorName;
        private Integer quantity;
        private Boolean extra;
        private Boolean leatherDelivered;
        private Boolean materialsDelivered;
        private Boolean dieCutReady;
    }
}
