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
public class ProductVariantLeatherResponse {
    private Long id;
    private Long productId;
    private Long colorId;
    private Long leatherMaterialId;
    private String leatherMaterialSku;
    private String leatherMaterialName;
    private BigDecimal qtyPerUnit;
}
