package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Una página de ítems de UNA OP, con su restante, para armar las tareas del día. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanOrderItemsResponse {
    private Long orderId;
    private String orderCode;
    private boolean onlineSale;
    private List<Item> content;
    private long totalElements;
    private int totalPages;
    private int size;
    private int number;
    private boolean first;
    private boolean last;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long productionOrderItemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private int totalQuantity;
        private int assignedQuantity;
        private int remainingQuantity;
        /** Horas por unidad (prd_time o 0.1). */
        private double prdTimePerUnit;
        private Integer unitsPerTask;
        /** prdTimePerUnit × remainingQuantity. */
        private double remainingHours;
        private String observations;
    }
}
