package com.fossiles.fossilescorebackend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String dpi;
    private LocalDate hireDate;
    private String position;
    private BigDecimal salary;
    private String bankAccount;
    private String paymentMethod;
    private BigDecimal igssDeduction;
    private BigDecimal quincenaBruta;
    private BigDecimal quincenaNeta;
    private Long departmentId;
    private Long costCenterId;
    private Long operationalUnitId;
    private String status;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private User user;
}

