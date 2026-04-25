package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAdjustmentRequest {
    private Long locationId; // Opcional: requerido solo para productos

    private Long materialId;
    private Long productId;
    private Long colorId; // Opcional: solo para productos con colores

    @NotNull(message = "System Stock is required")
    @DecimalMin(value = "0.0", message = "System Stock must be positive")
    private BigDecimal systemStock;

    @NotNull(message = "Physical Stock is required")
    @DecimalMin(value = "0.0", message = "Physical Stock must be positive")
    private BigDecimal physicalStock;

    @NotNull(message = "Reason is required")
    private String reason;
}

