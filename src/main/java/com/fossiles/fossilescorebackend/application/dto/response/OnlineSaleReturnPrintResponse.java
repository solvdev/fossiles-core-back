package com.fossiles.fossilescorebackend.application.dto.response;

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
public class OnlineSaleReturnPrintResponse {

    private Long returnId;
    private Long onlineSaleId;
    private String saleNumber;
    private String relatedShipmentNumber;

    private String customerName;
    private String address;
    private String phone;
    private String phone2;

    private String returnReason;
    private String itemCondition;
    private LocalDate returnDate;

    private BigDecimal totalAmount;
    private List<ReturnLine> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnLine {
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

