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
public class OnlineSaleDailySummaryResponse {
    private LocalDate date;
    private int totalSalesCount;
    private BigDecimal totalAmount;
    private BigDecimal totalNetAmount;
    private List<SellerSummary> bySeller;
    private List<GroupSummary> bySocialNetwork;
    private List<GroupSummary> byPaymentMethod;
    private Map<String, Integer> byStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SellerSummary {
        private String salesperson;
        private int salesCount;
        private BigDecimal totalAmount;
        private BigDecimal totalNetAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupSummary {
        private String name;
        private int salesCount;
        private BigDecimal totalAmount;
        private BigDecimal totalNetAmount;
    }
}
