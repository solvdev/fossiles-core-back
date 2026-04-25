package com.fossiles.fossilescorebackend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    private Long id;
    private String code;
    private Long productionOrderId;
    private String productionOrderCode;
    private Long productionOrderItemId;
    private Long productId;
    private String productName;
    private String productCode;
    private Long colorId;
    private String colorName;
    private Integer quantity;
    private String observations;
    private Integer desk;
    private Double estimatedHours;
    private LocalDate scheduledDate;
    private LocalDate deliveryDate;
    private Integer priority;
    private String status;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}
