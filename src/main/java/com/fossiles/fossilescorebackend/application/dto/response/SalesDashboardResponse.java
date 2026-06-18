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
public class SalesDashboardResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private ChannelSummary kiosko;
    private ChannelSummary online;
    private ChannelSummary vendor;
    private PeriodTotals totals;
    private List<TopProductSummary> topProducts;
    private List<MonthlyTrendPoint> monthlyTrend;
    private List<KioskOption> kiosks;
    private List<UnifiedSaleRow> recentSales;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelSummary {
        private String channel;
        private String label;
        private Integer salesCount;
        private BigDecimal totalAmount;
        private BigDecimal dailyAmount;
        private BigDecimal growthPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodTotals {
        private BigDecimal totalAmount;
        private BigDecimal dailyAmount;
        private BigDecimal growthPercent;
        private Integer salesCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopProductSummary {
        private String productName;
        private BigDecimal units;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyTrendPoint {
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
    public static class KioskOption {
        private Long kioskId;
        private String kioskCode;
        private String kioskName;
        private Integer salesCount;
        private BigDecimal totalAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnifiedSaleRow {
        private String id;
        private LocalDate saleDate;
        private String channel;
        private String channelLabel;
        private String reference;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal totalAmount;
        private String kioskName;
        private String sellerName;
    }
}
