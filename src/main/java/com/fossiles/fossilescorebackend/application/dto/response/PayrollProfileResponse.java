package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Datos de la ficha que usa la planilla, para revisarlos en un solo lugar. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollProfileResponse {
    private Long id;
    private String fullName;
    private String position;
    private Long departmentId;
    private String departmentName;
    private String status;
    private LocalDate hireDate;
    private LocalDate terminationDate;
    private String employmentType;
    private String payGroup;
    /** Grupo que le corresponde por su departamento. */
    private String departmentPayGroup;
    private String paymentMethod;
    private String bankAccount;
    private BigDecimal salary;
    private BigDecimal bonification;
    private String nit;
    private String igssAffiliation;
    private List<String> warnings;
}
