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
public class PurchaseOrderResponse {
    private Long id;
    private String code;
    private Long supplierId;
    private String supplierName;
    private LocalDate orderDate;
    private String status;
    private BigDecimal total;
    private String observations;
    private String referenceRequests;
    private List<Long> materialRequestIds;
    private Long costCenterId;
    private String costCenterName;
    private List<PurchaseOrderItemResponse> items;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}

