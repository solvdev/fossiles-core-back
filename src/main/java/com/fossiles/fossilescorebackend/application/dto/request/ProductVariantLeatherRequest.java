package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariantLeatherRequest {

    @NotNull
    private Long productId;

    /** Null = todos los colores */
    private Long colorId;

    @NotNull
    private Long leatherMaterialId;

    @NotNull
    @Builder.Default
    private BigDecimal qtyPerUnit = BigDecimal.ONE;
}
