package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Libro de descargas, de solo agregar (trigger en la base). Cada caso es su propia cuenta. */
@Entity
@Table(name = "recon_ledger_entry")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconLedgerEntryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Column(name = "batch_id")
    private Long batchId;
    @Column(name = "customer_code", length = 50)
    private String customerCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private Recon.LedgerType entryType;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
