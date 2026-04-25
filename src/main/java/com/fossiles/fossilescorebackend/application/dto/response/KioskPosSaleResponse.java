package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSaleResponse {
    private Long id;
    private String saleNumber;
    private LocalDate saleDate;
    private LocalDateTime soldAt;
    private Long kioskId;
    private String kioskCode;
    private String kioskName;
    private Long soldByUserId;
    private String soldByUsername;
    private String soldByName;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String paymentMethod;
    private String status;
    private BigDecimal totalItems;
    private BigDecimal discountAmount;
    private BigDecimal subtotal;
    private BigDecimal totalAmount;
    private String notes;
    private String comments;
    private Long promotionId;
    private String promotionName;
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
