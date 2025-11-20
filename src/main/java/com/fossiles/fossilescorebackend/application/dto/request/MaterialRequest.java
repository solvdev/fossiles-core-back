package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequest {
    @Size(max = 30, message = "SKU must not exceed 30 characters")
    private String sku;

    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @NotNull(message = "UOM ID is required")
    private Long uomId;

    @DecimalMin(value = "0.0", message = "Cost must be positive")
    private BigDecimal cost;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

