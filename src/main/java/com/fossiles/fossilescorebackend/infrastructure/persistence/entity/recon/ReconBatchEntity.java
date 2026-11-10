package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Lote de descarga. Al cerrarse congela sus casos y registra la descarga en el libro. */
@Entity
@Table(name = "recon_batch")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconBatchEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "code", nullable = false, length = 40)
    private String code;
    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;
    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private Recon.BatchStatus status = Recon.BatchStatus.OPEN;
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
    @Column(name = "total_expected", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalExpected = BigDecimal.ZERO;
    @Column(name = "total_received", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalReceived = BigDecimal.ZERO;
    @Column(name = "total_adjusted", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalAdjusted = BigDecimal.ZERO;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
    @Column(name = "closed_by")
    private Long closedBy;
    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;
    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
    @Column(name = "voided_by")
    private Long voidedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
