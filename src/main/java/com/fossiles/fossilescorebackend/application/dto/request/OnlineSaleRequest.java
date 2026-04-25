package com.fossiles.fossilescorebackend.application.dto.request;

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
public class OnlineSaleRequest {
    private String customerName;
    private String address;
    private String phone;
    private String phone2;
    private Boolean packaging;
    private String paymentMethod;
    private String invoiceTaxId;
    private String shippingCarrier;
    private LocalDate saleDate;
    private String socialNetwork;
    private String email;
    private String guideNumber;
    private String paymentAuthorization;
    private String status;
    private String observations;
    private String salesperson;

    // Campos para importación (valores pre-calculados del CSV)
    private String saleNumber;
    private BigDecimal shippingCost;
    private BigDecimal netAmount;

    /** Lista de productos en esta venta */
    private List<SaleItemRequest> items;

    // --- Campos legacy (para compatibilidad con ventas de un solo producto) ---
    private Long productId;
    private Long colorId;
    private String size;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleItemRequest {
        private Long productId;
        private String productName;
        private String productCode;
        private Long colorId;
        private String colorName;
        private String size;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
