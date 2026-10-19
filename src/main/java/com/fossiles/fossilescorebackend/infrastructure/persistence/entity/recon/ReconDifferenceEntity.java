package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Diferencia con su ajuste. Quien propone puede autorizar (decisión del negocio); se guardan ambos nombres. */
@Entity
@Table(name = "recon_difference")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconDifferenceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", nullable = false, length = 40)
    private Recon.DifferenceType diffType;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private Recon.DifferenceStatus status = Recon.DifferenceStatus.OPEN;
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
    @Column(name = "proposed_by")
    private Long proposedBy;
    @Column(name = "proposed_at")
    private LocalDateTime proposedAt;
    @Column(name = "authorized_by")
    private Long authorizedBy;
    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
