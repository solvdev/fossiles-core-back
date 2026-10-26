package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRequest {
    @Size(max = 100, message = "First name must not exceed 100 characters")
    private String firstName;

    @Size(max = 100, message = "Last name must not exceed 100 characters")
    private String lastName;

    @Email(message = "Email must be valid")
    @Size(max = 120, message = "Email must not exceed 120 characters")
    private String email;

    @Size(max = 30, message = "Phone must not exceed 30 characters")
    private String phone;

    @Size(max = 20, message = "DPI must not exceed 20 characters")
    private String dpi;

    private LocalDate hireDate;

    @Size(max = 100, message = "Position must not exceed 100 characters")
    private String position;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "El salario no puede ser negativo")
    private BigDecimal salary;

    @Size(max = 50, message = "Bank account must not exceed 50 characters")
    private String bankAccount;

    @Size(max = 50, message = "Payment method must not exceed 50 characters")
    private String paymentMethod;

    private BigDecimal igssDeduction;

    private BigDecimal quincenaBruta;

    private BigDecimal quincenaNeta;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "La bonificación no puede ser negativa")
    private BigDecimal bonification;

    /** PLANILLA o SERVICIOS. */
    @Size(max = 20, message = "Employment type must not exceed 20 characters")
    private String employmentType;

    /** PLANTA (5 y 20) o ADMINISTRACION (15 y fin de mes); si no se indica, sale del departamento. */
    @Size(max = 20, message = "Pay group must not exceed 20 characters")
    private String payGroup;

    @Size(max = 20, message = "NIT must not exceed 20 characters")
    private String nit;

    @Size(max = 30, message = "IGSS affiliation must not exceed 30 characters")
    private String igssAffiliation;

    private LocalDate terminationDate;

    /** En una edición, un campo vacío conserva el valor; estos indicadores lo borran a propósito. */
    private Boolean clearBonification;

    private Boolean clearTerminationDate;

    private Long departmentId;

    private Long costCenterId;

    private Long operationalUnitId;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    private Long userId;
}

