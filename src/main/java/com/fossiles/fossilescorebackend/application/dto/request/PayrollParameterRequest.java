package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Nuevo valor de un parámetro legal de planilla, vigente desde una fecha. */
@Data
public class PayrollParameterRequest {
    @NotBlank
    @Size(max = 50)
    private String code;
    @NotNull
    @DecimalMin("0")
    private BigDecimal value;
    @NotNull
    private LocalDate effectiveFrom;
    @Size(max = 200)
    private String description;
}
