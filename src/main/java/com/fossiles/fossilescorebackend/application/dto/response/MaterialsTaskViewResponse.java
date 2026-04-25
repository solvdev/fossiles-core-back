package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista para el equipo de materiales: muestra tareas con sus recetas (BOM).
 * Permite saber qué materiales despachar para cada tarea.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialsTaskViewResponse {
    private Long taskId;
    private String taskCode;
    private String productionOrderCode;
    private Long productionOrderId;
    private String orderType;
    private Integer desk;
    private LocalDate scheduledDate;
    private String startTime;
    private Double estimatedHours;
    private String status;
    private Boolean leatherDelivered;
    private LocalDateTime leatherDeliveredAt;
    private Boolean dieCutReady;
    private LocalDate dieCutDate;
    private Boolean materialsDelivered;
    private LocalDateTime materialsDeliveredAt;
    private Boolean requiresMaterials;
    private String workflowStatus;
    private Boolean canDeliverMaterials;
    private LocalDateTime completedAt;
    private List<TaskProductWithRecipe> products;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskProductWithRecipe {
        private Long taskItemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private Boolean requiresMaterials;
        private Boolean leatherDelivered;
        private LocalDateTime leatherDeliveredAt;
        private Boolean materialsDelivered;
        private LocalDateTime materialsDeliveredAt;
        private Boolean canDeliverMaterials;
        private List<RecipeMaterial> recipe;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeMaterial {
        private Long materialId;
        private String materialName;
        private String materialSku;
        private BigDecimal quantityPerUnit;
        private BigDecimal totalQuantity;
        private BigDecimal availableStock;
        private Boolean sufficientStock;
        private String measurementUnit;
    }
}

