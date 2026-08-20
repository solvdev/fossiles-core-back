package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    /** Crédito de empaques SUM aplicado a la liquidación (0 si no hay diferencia de producto). */
    private BigDecimal packagingReturnedAmount;
    /** Crédito de empaques SUM de la factura original (potencial; no siempre se aplica). */
    private BigDecimal packagingCreditAmount;
    /** Precio de catálogo de empaques SUM incluido en el egreso (sin movimiento de stock). */
    /** Precio de catálogo de empaques SUM incluido en el egreso (sin movimiento de stock). */
    private BigDecimal packagingGivenAmount;

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
        private String hardwareCondition;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
