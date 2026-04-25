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
public class TaskTicketResponse {
    // Tarea
    private Long taskId;
    private String taskCode;
    private Integer desk;
    private LocalDate scheduledDate;
    private String startTime;             // "HH:mm"
    private Double estimatedHours;        // Total hours for the task
    private String status;
    private LocalDateTime completedAt;
    private Boolean dieCutReady;

    // Orden de producción
    private String productionOrderCode;
    private LocalDate deliveryDate;
    private String orderObservations;

    // Productos de la tarea
    private List<TicketItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TicketItem {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private Double estimatedHours;
        private String observations;
        private Boolean daySaleExtra;
    }
}
