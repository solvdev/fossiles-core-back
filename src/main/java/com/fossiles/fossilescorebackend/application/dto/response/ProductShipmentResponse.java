package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentResponse {
    private Long id;
    private Long distributionId;
    /** OP (OPI/OPCK) cuando el envío no usa distribución. */
    private Long productionOrderId;
    private Long partialReleaseId;
    private String productionOrderCode;
    private String distributionNumber;
    private String shipmentNumber;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String status;
    private String notes;
    private List<PackingItemResponse> packingItems;
    private LocalDateTime sentAt;
    private Long sentBy;
    private LocalDateTime receivedAt;
    private Long receivedBy;
    private String receivedNotes;
    private List<ProductShipmentDetailResponse> products;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackingItemResponse {
        private Long materialId;
        private java.math.BigDecimal quantity;
        private java.math.BigDecimal unitPrice;
    }
}

