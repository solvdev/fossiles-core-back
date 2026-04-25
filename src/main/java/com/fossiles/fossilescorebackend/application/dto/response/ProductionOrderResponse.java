package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderResponse {
    private Long id;
    private String code;
    private String orderType; // CINCHOS, MARCAS, NORMAL, DISTRIBUTION
    private Long customerId;
    private String customerName;
    private String sellerName;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private String observations;
    private Boolean materialsConsumed;
    private LocalDateTime materialsConsumedAt;
    private String status; // PENDING, IN_PROGRESS, IN_QA, COMPLETED, CANCELLED
    private Long distributionId;
    private BigDecimal shippingCost;
    private List<PackingItemResponse> packingItems;
    private String distributionNumber;
    private LocalDate distributionDate;
    private List<ProductShipmentResponse> distributionShipments;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private List<ProductionOrderItemResponse> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackingItemResponse {
        private Long materialId;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private String materialCode;
        private String materialName;
    }
}

