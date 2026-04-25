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

    private BigDecimal salary;

    @Size(max = 50, message = "Bank account must not exceed 50 characters")
    private String bankAccount;

    @Size(max = 50, message = "Payment method must not exceed 50 characters")
    private String paymentMethod;

    private BigDecimal igssDeduction;

    private BigDecimal quincenaBruta;

    private BigDecimal quincenaNeta;

    private Long departmentId;

    private Long costCenterId;

    private Long operationalUnitId;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    private Long userId;
}

