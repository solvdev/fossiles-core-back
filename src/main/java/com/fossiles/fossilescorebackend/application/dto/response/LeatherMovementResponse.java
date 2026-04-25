package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class LeatherMovementResponse {
    private Long id;
    private String movementType;
    private Long materialId;
    private String materialName;
    private String materialSku;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private LocalDate movementDate;
    private Long supplierId;
    private String supplierName;
    private String purchaseDocument;
    private Long productionOrderId;
    private String productionOrderCode;
    private String deliveredBy;
    private String receivedBy;
    private String observations;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;
    private Long createdBy;
}

