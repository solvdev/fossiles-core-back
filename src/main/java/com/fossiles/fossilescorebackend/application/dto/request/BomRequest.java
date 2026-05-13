package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomRequest {
    @Size(max = 100, message = "BOM name must not exceed 100 characters")
    private String bomName;

    @NotNull(message = "Product ID is required")
    private Long productId;

    private Long colorId;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    /** Configuración de cuero por producto/color asociada a esta BOM. */
    private Long leatherMaterialId;
    private BigDecimal leatherQtyPerUnit;

    private List<BomItemRequest> items;
}

