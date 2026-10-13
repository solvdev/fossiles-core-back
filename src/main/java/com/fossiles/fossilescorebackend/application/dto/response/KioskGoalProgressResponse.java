package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskGoalProgressResponse {
    private Long kioskLocationId;
    private String kioskName;
    private Integer goalYear;
    private Integer goalMonth;
    private BigDecimal goalAmount;
    private BigDecimal soldAmount;
    private BigDecimal percentAchieved;
    /** NONE | TIER1 | TIER2 | TIER3 */
    private String commissionTier;
    private BigDecimal commissionAmount;
    private boolean hasGoalConfigured;
}
