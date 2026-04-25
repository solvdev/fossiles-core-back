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
public class PurchaseCompensationResponse {
    private Long id;
    private Long sourcePurchaseId;
    private String sourcePurchaseNumber;
    private String sourcePurchaseDescription;
    private Long targetPurchaseId;
    private String targetPurchaseNumber;
    private String targetPurchaseDescription;
    private BigDecimal amount;
    private String description;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}

