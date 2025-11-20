package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
public class TaxRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 20, message = "Code must not exceed 20 characters")
    private String code;

    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @NotNull(message = "Percentage is required")
    @DecimalMin(value = "0.0", message = "Percentage must be positive")
    @DecimalMax(value = "100.0", message = "Percentage must not exceed 100")
    private BigDecimal percentage;
}

