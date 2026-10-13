package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

public class SalesDashboardResponse {

    private SalesDashboardResponse() {
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
