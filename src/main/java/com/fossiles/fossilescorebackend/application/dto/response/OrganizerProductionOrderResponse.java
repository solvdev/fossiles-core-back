package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * OP con sus ítems y cantidad restante sin tarea, para el Organizador de Tareas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerProductionOrderResponse {
    private Long id;
    private String code;
    private String orderType;
    /** OPL | OPV | OPK | OPI | OPCK | OPD (etiqueta de familia para filtros). */
    private String family;
    /** true si la OP es venta en línea (puede agregarse como extra sobre las 4h). */
    private boolean onlineSale;
    private String status;
    private String customerName;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private LocalDateTime createdAt;
    private List<OrganizerItemResponse> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizerItemResponse {
        private Long productionOrderItemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        /** Cantidad efectiva del ítem (quantity + tallas). */
        private int totalQuantity;
        /** Cantidad ya cubierta por tareas no canceladas. */
        private int assignedQuantity;
        /** Cantidad disponible para nuevas tareas. */
        private int remainingQuantity;
        /** Horas por unidad (prd_time del producto o 0.1 por defecto). */
        private double prdTimePerUnit;
        /** Desglose de tallas de la OP (informativo). */
        private Map<String, Integer> sizes;
        private String observations;
        /** Tareas no canceladas que ya cubren este ítem (mesa / fecha). */
        private List<OrganizerItemAssignment> assignments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizerItemAssignment {
        private Long taskId;
        private String taskCode;
        private Integer desk;
        private LocalDate scheduledDate;
        private Integer quantity;
        private String status;
    }
}
