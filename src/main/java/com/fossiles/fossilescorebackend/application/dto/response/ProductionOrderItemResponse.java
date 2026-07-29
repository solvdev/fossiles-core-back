package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderItemResponse {
    private Long id;
    private Long productionOrderId;
    private Long onlineSaleId;
    private Long productId;
    private String productName;
    private String productCode;
    private Long colorId;
    private String colorName;
    private String brandName;
    private Integer quantity;
    private Integer warehouseReceivedQty;
    private BigDecimal leatherConsumption; // ft² por unidad
    private BigDecimal leatherTotal;      // ft² total = consumption × quantity
    private Map<String, Integer> sizes; // Para cinchos: talla -> cantidad
    private String observations;
    private BigDecimal unitPrice;
    /** Precios unitarios por talla cuando difieren del unitPrice global. */
    private Map<String, BigDecimal> unitPrices;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

