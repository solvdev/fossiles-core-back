package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorAggregateDashboardResponse {
    private Long supervisorUserId;
    private String supervisorName;
    private Integer goalYear;
    private Integer goalMonth;
    private BigDecimal aggregateGoalAmount;
    private BigDecimal aggregateSoldAmount;
    private BigDecimal percentAchieved;
    /** NONE | TIER1 | TIER2 | TIER3 */
    private String commissionTier;
    private BigDecimal commissionAmount;
    private List<KioskGoalProgressResponse> kiosks;
}
