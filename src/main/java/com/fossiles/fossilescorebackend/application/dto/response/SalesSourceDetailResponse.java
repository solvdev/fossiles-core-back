package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesSourceDetailResponse {
    private String channel;
    private String label;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate previousStartDate;
    private LocalDate previousEndDate;
    private SourceKpis kpis;
    private List<DailyPoint> dailySeries;
    private List<TrendPoint> monthlyTrend;
    private List<ProductRank> topProducts;
    private List<SaleRow> recentSales;
    private Map<String, List<BreakdownRow>> breakdowns;
    private List<KioskOption> kioskOptions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceKpis {
        private BigDecimal totalAmount;
        private BigDecimal productAmount;
        private BigDecimal packagingAmount;
        private BigDecimal shippingAmount;
        private BigDecimal previousTotalAmount;
        private BigDecimal growthPercent;
        private BigDecimal dailyAmount;
        private int salesCount;
        private BigDecimal unitsFinished;
        private BigDecimal avgTicket;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPoint {
        private LocalDate date;
        private BigDecimal amount;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {
        private String label;
        private int year;
        private int month;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRank {
        private Long productId;
        private String productCode;
        private String productName;
        private BigDecimal units;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BreakdownRow {
        private String key;
        private String label;
        private int count;
        private BigDecimal amount;
        private BigDecimal sharePercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleRow {
        private Long id;
        private LocalDate saleDate;
        private String reference;
        private String productLabel;
        private BigDecimal quantity;
        private BigDecimal totalAmount;
        private String status;
        private String party;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskOption {
        private Long kioskId;
        private String kioskCode;
        private String kioskName;
    }
}
