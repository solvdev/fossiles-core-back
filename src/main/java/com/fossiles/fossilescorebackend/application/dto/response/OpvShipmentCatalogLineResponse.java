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
public class OpvShipmentCatalogLineResponse {
    /** PRODUCT o PACKING */
    private String lineType;
    private String productCode;
    private String productName;
    private String colorName;
    private String sizeLabel;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    private String materialName;
}
