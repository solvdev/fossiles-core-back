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
public class OnlineSaleResponse {
    private Long id;
    private String saleNumber;
    private String customerName;
    private String address;
    private String phone;
    private String phone2;
    private Boolean packaging;
    private String paymentMethod;
    private String paymentMethodDisplay;
    private String invoiceTaxId;
    private BigDecimal totalAmount;
    private BigDecimal shippingCost;
    private BigDecimal netAmount;
    private String shippingCarrier;
    private LocalDate saleDate;
    private String socialNetwork;
    private String email;
    private String shipmentNumber;
    private String guideNumber;
    private String paymentAuthorization;
    private String status;
    private String observations;
    private String salesperson;
    private Boolean inProductionOrder;
    private Long productionOrderId;
    private LocalDateTime createdAt;
    private Long createdBy;

    /** Lista de productos en esta venta */
    private List<SaleItemResponse> items;

    private Long invoiceId;
    private String invoiceStatus;
    private String invoiceFelUuid;
    private String invoiceFelSerie;
    private String invoiceFelNumero;
    private String invoiceFelError;

    // --- Campos legacy para compatibilidad con frontend existente ---
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private String size;
    private Integer quantity;
    private BigDecimal unitPrice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleItemResponse {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }
}
