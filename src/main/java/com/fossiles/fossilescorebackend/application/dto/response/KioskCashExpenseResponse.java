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
public class KioskCashExpenseResponse {
    private Long id;
    private Long cashSessionId;
    private Long kioskSaleId;
    private String saleNumber;
    private String internalNumber;
    private BigDecimal amount;
    private String description;
    private LocalDateTime createdAt;
    private Long createdByUserId;
    private String createdByName;
}
