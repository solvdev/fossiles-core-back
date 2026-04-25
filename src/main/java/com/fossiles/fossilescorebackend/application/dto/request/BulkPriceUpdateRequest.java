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
public class BulkPriceUpdateRequest {
    @NotNull(message = "Percentage is required")
    @DecimalMin(value = "-100.0", message = "Percentage must be at least -100")
    private BigDecimal percentage;

    private Long categoryId; // Si es null, aplica a todos los productos
}

