package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangeSlipResponse {
    private Long id;
    private String slipNumber;
    private String slipType;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
    private Long originalSaleId;
    private String originalSaleNumber;
    private Long originalSaleItemId;
    private Long returnedProductId;
    private String returnedProductCode;
    private String returnedProductName;
    private Long returnedColorId;
    private String returnedColorName;
    private String returnedSize;
    private BigDecimal returnedQuantity;
    private BigDecimal returnedAmount;
    private Long givenProductId;
    private String givenProductCode;
    private String givenProductName;
    private Long givenColorId;
    private String givenColorName;
    private String givenSize;
    private BigDecimal givenQuantity;
    private BigDecimal givenAmount;
    private BigDecimal differenceAmount;
    private Long newSaleId;
    private String newSaleNumber;
    private Boolean apto;
    private String status;
    private String reason;
    private String observations;
    private Long createdByUserId;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime reintegratedAt;
    private Long reintegratedByUserId;
    private String reintegratedByName;
    private Long authorizedByUserId;
    private String authorizedByName;
    private LocalDateTime authorizedAt;
    private String rejectionReason;
    private Long returnMovementId;
    private Long givenMovementId;
}
