package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Comisión del vendedor por caso: 2% del neto sin envío. Se congela al cerrar el mes. */
@Entity
@Table(name = "recon_commission_line")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconCommissionLineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;
    @Column(name = "month", nullable = false, length = 7)
    private String month;
    @Column(name = "base_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseAmount;
    @Column(name = "rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;
    @Column(name = "commission_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal commissionAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Recon.CommissionStatus status = Recon.CommissionStatus.CALCULADA;
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;
    @Column(name = "locked_by")
    private Long lockedBy;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
