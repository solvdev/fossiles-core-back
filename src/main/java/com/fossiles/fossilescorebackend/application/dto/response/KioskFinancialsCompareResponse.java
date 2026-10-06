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
    /** SAME_PERIOD | FULL_MONTH | CUSTOM. */
    private String mode;
    private LocalDate asOf;
    /** Solo CUSTOM: periodo actual y periodo de comparacion elegidos (fechas exactas). */
    private LocalDate from;
    private LocalDate to;
    private LocalDate baseFrom;
    private LocalDate baseTo;
    private List<SiteCompare> sites;
    private Totals totals;
    private List<MonthCompare> monthly;
    /** Solo CUSTOM: ventas dia a dia (el dia N del periodo actual contra el dia N del de comparacion). */
    private List<DayCompare> daily;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayCompare {
        /** 1 = primer dia de cada periodo. */
        private Integer index;
        /** null si el periodo actual es mas corto que el de comparacion. */
        private LocalDate date;
        /** null si el periodo de comparacion es mas corto que el actual. */
        private LocalDate baseDate;
        private BigDecimal sales;
        private BigDecimal baseSales;
    }
}
