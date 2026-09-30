package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** GET /api/kiosk-financials/compare. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsCompareResponse {

    private Integer year;
    private Integer baseYear;
    /** SAME_PERIOD | FULL_MONTH. */
    private String mode;
    private LocalDate asOf;
    private List<SiteCompare> sites;
    private Totals totals;
    private List<MonthCompare> monthly;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteCompare {
        private Long siteId;
        private String name;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private LocalDate basePeriodFrom;
        private LocalDate basePeriodTo;
        private BigDecimal sales;
        private BigDecimal baseSales;
        private BigDecimal delta;
        /** null si baseSales = 0. */
        private BigDecimal deltaPct;
        private BigDecimal goalPct;
        private BigDecimal baseGoalPct;
        private BigDecimal margin;
        private BigDecimal baseMargin;
        private BigDecimal difference;
        private BigDecimal baseDifference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private BigDecimal sales;
        private BigDecimal baseSales;
        private BigDecimal delta;
        private BigDecimal deltaPct;
        private BigDecimal margin;
        private BigDecimal baseMargin;
        private BigDecimal difference;
        private BigDecimal baseDifference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthCompare {
        private Integer month;
        private BigDecimal sales;
        private BigDecimal baseSales;
        private BigDecimal totalCost;
        private BigDecimal baseTotalCost;
        private BigDecimal margin;
        private BigDecimal baseMargin;
    }
}
