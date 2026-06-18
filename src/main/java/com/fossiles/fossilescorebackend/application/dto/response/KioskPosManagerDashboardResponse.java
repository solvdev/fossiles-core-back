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
public class KioskPosManagerDashboardResponse {
    private Metric today;
    private Metric todayLastYear;
    private Metric lastMonth;
    private Metric monthToDate;
    private BigDecimal growthVsLastYearPercent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metric {
        private BigDecimal amount;
        private Integer count;
    }
}
