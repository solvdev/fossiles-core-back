package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Planilla de un grupo para una fecha de pago: BORRADOR se recalcula, APROBADA se congela, PAGADA se contabiliza. */
@Entity
@Table(name = "payroll_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pay_group", nullable = false, length = 20)
    private String payGroup;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "pay_date", nullable = false)
    private LocalDate payDate;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "BORRADOR";

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(length = 500)
    private String notes;

    @Column(name = "total_gross", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalGross = BigDecimal.ZERO;

    @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalNet = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    /** Huella de fichas, cargos y parámetros usados en el cálculo; si cambia, el borrador está desactualizado. */
    @Column(name = "calculation_hash", length = 64)
    private String calculationHash;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "paid_by")
    private Long paidBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = GuatemalaDateTime.now();
    }
}
