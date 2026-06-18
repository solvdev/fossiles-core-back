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
public class ProductDistributionResponse {
    private Long id;
    private String distributionNumber;
    private LocalDate distributionDate;
    private String status;
    private String description;
    private Integer shipmentCount;
    private List<ProductShipmentResponse> shipments;
    private Long productionOrderId;
    private String productionOrderCode;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

