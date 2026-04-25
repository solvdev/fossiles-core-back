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
public class MaterialReceiptResponse {
    private Long id;
    private Long purchaseOrderId;
    private String purchaseOrderCode;
    private LocalDate receiptDate;
    private String observations;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
    
    // Información adicional de la orden
    private String supplierName;
    private LocalDate orderDate;
    private String orderStatus;
    private BigDecimal orderTotal;
    private String orderObservations;
    private List<Long> materialRequestIds;
    private List<MaterialReceiptItemResponse> items;
}

