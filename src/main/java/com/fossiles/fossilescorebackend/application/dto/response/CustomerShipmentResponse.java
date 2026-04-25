package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Agrupa los items de una orden de producción por venta online (cliente).
 * Cada grupo representa un envío independiente al cliente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerShipmentResponse {
    private Long onlineSaleId;
    private String saleNumber;
    private String customerName;
    private String address;
    private String phone;
    private String phone2;
    private String shipmentNumber;
    private String shippingCarrier;
    private String guideNumber;
    private String paymentMethod;
    private String saleStatus;
    private LocalDate saleDate;
    private BigDecimal totalAmount;
    private BigDecimal shippingCost;
    private Boolean packaging;
    private List<ShipmentItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipmentItem {
        private Long productionOrderItemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private String size;
    }
}

