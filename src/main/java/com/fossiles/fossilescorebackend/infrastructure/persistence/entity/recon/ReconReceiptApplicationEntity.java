package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Aplicación de un fondo a un caso. Un fondo puede repartirse entre casos y un caso puede recibir varios fondos. */
@Entity
@Table(name = "recon_receipt_application")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconReceiptApplicationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;
    @Column(name = "amount_applied", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountApplied;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", nullable = false, length = 15)
    private Recon.MatchMethod matchMethod;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private Recon.RowStatus status = Recon.RowStatus.ACTIVE;
    @Column(name = "applied_by")
    private Long appliedBy;
    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;
    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;
    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
    @Column(name = "voided_by")
    private Long voidedBy;

    @PrePersist
    void onCreate() {
        if (appliedAt == null) appliedAt = LocalDateTime.now();
    }
}
