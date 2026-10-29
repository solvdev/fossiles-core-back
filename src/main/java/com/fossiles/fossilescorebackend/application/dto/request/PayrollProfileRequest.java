package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reemplaza todos los datos de planilla de la ficha: un campo vacío queda vacío. */
@Data
public class PayrollProfileRequest {
    @NotBlank
    private String employmentType;
    @NotBlank
    private String payGroup;
    @NotBlank
    private String paymentMethod;
    @Size(max = 50)
    private String bankAccount;
    @NotNull
    @DecimalMin("0")
    private BigDecimal salary;
    /** Vacío: se usa la bonificación incentivo de ley. */
    @DecimalMin("0")
    private BigDecimal bonification;
    private LocalDate terminationDate;
    @Size(max = 20)
    private String nit;
    @Size(max = 30)
    private String igssAffiliation;
}
