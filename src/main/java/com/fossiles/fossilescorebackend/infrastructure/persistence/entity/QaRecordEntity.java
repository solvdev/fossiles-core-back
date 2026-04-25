package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "qa_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "quantity_delivered", nullable = false)
    @Builder.Default
    private Integer quantityDelivered = 0;

    @Column(name = "quantity_approved", nullable = false)
    @Builder.Default
    private Integer quantityApproved = 0;

    @Column(name = "quantity_rejected", nullable = false)
    @Builder.Default
    private Integer quantityRejected = 0;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED, REWORK

    @Column(name = "delivered_by", length = 150)
    private String deliveredBy;

    @Column(name = "approved_by", length = 150)
    private String approvedBy;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

