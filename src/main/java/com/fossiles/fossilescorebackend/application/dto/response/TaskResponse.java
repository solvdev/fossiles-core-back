package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private Long id;
    private String code;
    private Long productionOrderId;
    private String productionOrderCode;
    // Legacy single-product fields (kept for backward compat)
    private Long productionOrderItemId;
    private Long productId;
    private String productName;
    private String productCode;
    private Long colorId;
    private String colorName;
    private Integer quantity;
    private String observations;
    // Scheduling
    private Integer desk;
    private Double estimatedHours;
    private LocalDate scheduledDate;
    private LocalDate deliveryDate;
    private Integer priority;
    private String startTime;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer actualDurationMinutes;
    private Integer wasteQuantity;
    private String wasteNotes;
    private Boolean leatherDelivered;
    private LocalDateTime leatherDeliveredAt;
    private Boolean dieCutReady;
    private LocalDate dieCutDate;
    private Boolean materialsDelivered;
    private LocalDateTime materialsDeliveredAt;
    private Boolean requiresMaterials;
    private String workflowStatus;
    private Boolean canDeliverMaterials;
    private String status;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    // Items (multiple products per task)
    private List<TaskItemDTO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItemDTO {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private Double estimatedHours;
        private String observations;
        private Boolean requiresMaterials;
        private Boolean leatherDelivered;
        private LocalDateTime leatherDeliveredAt;
        private Boolean materialsDelivered;
        private LocalDateTime materialsDeliveredAt;
        private Boolean daySaleExtra;
    }
}
