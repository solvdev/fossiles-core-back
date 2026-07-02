package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangePreviewResponse {
    private Long originalSaleId;
    private String originalSaleNumber;
    private LocalDate originalSaleDate;
    private Long originalSaleItemId;
    private ProductLine returned;
    private ProductLine given;
    private BigDecimal returnedAmount;
    private BigDecimal givenAmount;
    private BigDecimal differenceAmount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductLine {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
