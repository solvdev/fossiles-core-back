package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Deuda de un empleado por una compra de colaborador. Con DESCUENTO la planilla la rebaja; TRANSFERENCIA o
 * EFECTIVO se cobran fuera de planilla. Sin forma definida queda a la espera de Contabilidad.
 */
@Entity
@Table(name = "employee_charge")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeChargeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "settlement_method", length = 20)
    private String settlementMethod;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDIENTE";

    @Column(name = "charged_on", nullable = false)
    private LocalDate chargedOn;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = GuatemalaDateTime.now();
    }
}
