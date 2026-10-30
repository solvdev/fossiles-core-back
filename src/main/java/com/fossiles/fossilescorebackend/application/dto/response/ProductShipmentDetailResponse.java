package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentDetailResponse {
    private Long id;
    private Long shipmentId;
    private Long productId;
    private String productCode;
    private String productName;
    private String productImageUrl;
    private Long categoryId;
    private String categoryName;
    private Long colorId;
    private String colorName;
    private String size;
    /** NUEVO | VIEJO */
    private String hardwareCondition;
    /** Marca OPV (derivada del ítem de la orden o de hardware_condition). */
    private String brandName;
    private BigDecimal quantity;
    /** Precio unitario de la línea (override por talla / cliente). */
    private BigDecimal unitPrice;
    private BigDecimal quantityReceived;
    private BigDecimal quantityDifference;
    private String receivedLineNotes;
}

