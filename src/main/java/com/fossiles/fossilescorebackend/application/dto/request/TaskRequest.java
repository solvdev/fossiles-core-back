package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRequest {
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code;

    private Long productionOrderId;
    private Long productionOrderItemId;
    private Long productId;
    private Integer quantity;
    private Integer desk;
    private Double estimatedHours;
    private LocalDate scheduledDate;
    private LocalDate deliveryDate;
    private Integer priority;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}
