package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Parte de un cargo descontada en una línea de planilla. */
@Entity
@Table(name = "employee_charge_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeChargeApplicationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_id", nullable = false)
    private Long chargeId;

    @Column(name = "run_line_id", nullable = false)
    private Long runLineId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
