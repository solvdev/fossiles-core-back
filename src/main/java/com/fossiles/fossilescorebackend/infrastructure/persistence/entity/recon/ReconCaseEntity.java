package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Un caso de conciliación por venta/ENVL. Guarda una foto de lo cobrado; los saldos se recalculan, nunca se editan a mano. */
@Entity
@Table(name = "recon_case")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconCaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "online_sale_id", nullable = false)
    private Long onlineSaleId;
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 10)
    @Builder.Default
    private Recon.Origin origin = Recon.Origin.NORMAL;
    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;
    @Column(name = "sale_number", length = 30)
    private String saleNumber;
    @Column(name = "shipment_number", length = 30)
    private String shipmentNumber;
    @Column(name = "guide_number", length = 100)
    private String guideNumber;
    @Column(name = "carrier", length = 50)
    private String carrier;
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;
    @Column(name = "salesperson", length = 100)
    private String salesperson;
    @Column(name = "seller_id")
    private Long sellerId;
    @Column(name = "expected_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedAmount;
    @Column(name = "received_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal receivedAmount = BigDecimal.ZERO;
    @Column(name = "difference", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal difference = BigDecimal.ZERO;
    @Column(name = "authorized_adjustment", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal authorizedAdjustment = BigDecimal.ZERO;
    @Column(name = "pending_balance", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal pendingBalance = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Recon.Status status = Recon.Status.PENDIENTE_REVISION;
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;
    @Column(name = "manual_reconciled", nullable = false)
    private boolean manualReconciled;
    @Column(name = "excluded_reason", length = 300)
    private String excludedReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "problem_type", length = 40)
    private Recon.DifferenceType problemType;
    @Column(name = "critical_count", nullable = false)
    private int criticalCount;
    @Column(name = "warning_count", nullable = false)
    private int warningCount;
    @Column(name = "batch_id")
    private Long batchId;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
    @Version
    @Column(name = "version", nullable = false)
    private int version;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isClosed() {
        return closedAt != null;
    }
}
