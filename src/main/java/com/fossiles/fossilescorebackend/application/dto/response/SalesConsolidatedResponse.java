package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesConsolidatedResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate previousStartDate;
    private LocalDate previousEndDate;
    private SalesSourceDetailResponse.SourceKpis totals;
    private List<SourceSummary> sources;
    private List<MonthlyPoint> monthlyTrend;
    private List<DailySourcePoint> dailySeries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceSummary {
        private String channel;
        private String label;
        private SalesSourceDetailResponse.SourceKpis kpis;
        private BigDecimal sharePercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyPoint {
        private String label;
        private int year;
        private int month;
        private BigDecimal kiosko;
        private BigDecimal online;
        private BigDecimal vendor;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailySourcePoint {
        private LocalDate date;
        private BigDecimal kiosko;
        private BigDecimal online;
        private BigDecimal vendor;
        private BigDecimal total;
    }
}
