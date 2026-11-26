package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Envío directo a un cliente de Luis Felipe (sin OP), con lo que se le cargó en cuentas por cobrar. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectSaleShipmentResponse {
    private Long id;
    private String shipmentNumber;
    private String status;
    private Long customerId;
    private String customerName;
    private String kind;
    private String source;
    private String sourceLabel;
    private BigDecimal shippingCost;
    private String notes;
    private BigDecimal productsTotal;
    private Integer units;
    /** Cargo activo en cuentas por cobrar (null si todavía no se envía o si se anuló). */
    private Long chargeEntryId;
    private BigDecimal chargeAmount;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private String createdByName;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long detailId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }
}
