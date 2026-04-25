package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyLiquidationRequest {
    private BigDecimal realShippingCost;
    private BigDecimal forzaCommission;
    private BigDecimal guatexCommission;
    private BigDecimal shortfall;
    private BigDecimal ivaRate;
    private BigDecimal commissionRate;
    private String notes;
}

