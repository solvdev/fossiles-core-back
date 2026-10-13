package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class KioskGoalUpsertRequest {
    @NotNull
    @Min(2020)
    @Max(2100)
    private Integer goalYear;

    @NotNull
    @Min(1)
    @Max(12)
    private Integer goalMonth;

    @NotNull
    @DecimalMin(value = "0", message = "La meta no puede ser negativa")
    private BigDecimal goalAmount;
}
