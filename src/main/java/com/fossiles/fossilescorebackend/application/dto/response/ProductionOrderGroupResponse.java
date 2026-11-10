package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** OP consolidada con sus órdenes y, en el detalle, los productos sumados para la hoja. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderGroupResponse {
    private Long id;
    private String code;
    /** OPV | OPC */
    private String family;
    /** ACTIVE | DISSOLVED */
    private String status;
    private String observations;
    private String memberCodes;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime dissolvedAt;
    /** La entrega más próxima de sus órdenes. */
    private LocalDate deliveryDate;
    private int totalUnits;
    private List<OrderRef> orders;
    /** Solo en el detalle: productos sumados de todas las órdenes. */
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderRef {
        private Long id;
        private String code;
        private String orderType;
        private String status;
        private String customerName;
        private LocalDate deliveryDate;
        private int units;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String brandName;
        private int quantity;
        /** Tallas sumadas (talla → unidades), si las hay. */
        private Map<String, Integer> sizes;
        /** De qué OP sale cada parte: código → unidades. */
        private Map<String, Integer> byOrder;
    }
}
