package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Tasa o monto legal de planilla con vigencia: aplica el registro más reciente con fecha menor o igual al período. */
@Entity
@Table(name = "payroll_parameter")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollParameterEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(name = "param_value", nullable = false, precision = 14, scale = 4)
    private BigDecimal value;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(length = 200)
    private String description;
}
