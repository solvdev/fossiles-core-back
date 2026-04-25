package com.fossiles.fossilescorebackend.application.dto.request;

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
public class ManufacturingConfigRequest {
    @NotNull(message = "Payroll Cinchos is required")
    @Min(value = 0, message = "Payroll Cinchos must be greater than or equal to 0")
    private BigDecimal payrollCinchos;

    @NotNull(message = "Payroll Mesas is required")
    @Min(value = 0, message = "Payroll Mesas must be greater than or equal to 0")
    private BigDecimal payrollMesas;

    @NotNull(message = "Payroll Warehouse is required")
    @Min(value = 0, message = "Payroll Warehouse must be greater than or equal to 0")
    private BigDecimal payrollWarehouse;

    @NotNull(message = "Minutes Cinchos is required")
    @Min(value = 1, message = "Minutes Cinchos must be greater than 0")
    private Integer minutesCinchos;

    @NotNull(message = "Minutes Mesas is required")
    @Min(value = 1, message = "Minutes Mesas must be greater than 0")
    private Integer minutesMesas;

    @NotNull(message = "Number of Tables Mesas is required")
    @Min(value = 1, message = "Number of Tables Mesas must be greater than 0")
    private Integer numberOfTablesMesas;
}

