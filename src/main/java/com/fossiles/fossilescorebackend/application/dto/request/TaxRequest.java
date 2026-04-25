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
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code;

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @NotNull(message = "Percentage is required")
    @DecimalMin(value = "0.00", message = "Percentage must be greater than or equal to 0")
    @DecimalMax(value = "100.00", message = "Percentage must be less than or equal to 100")
    private BigDecimal percentage;

    @Size(max = 50, message = "Type must not exceed 50 characters")
    private String type;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

