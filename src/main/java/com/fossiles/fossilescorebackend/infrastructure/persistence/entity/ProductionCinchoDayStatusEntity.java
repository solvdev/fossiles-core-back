package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "production_cincho_day_status",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"work_date", "production_order_id", "production_order_item_id"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionCinchoDayStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "production_order_item_id", nullable = false)
    private Long productionOrderItemId;

    @Column(nullable = false)
    private Boolean delivered;

    /** PENDING | IN_PROGRESS | COMPLETED (control visual mesa cinchos, sin tiempos). */
    @Column(name = "work_status", nullable = false, length = 20)
    private String workStatus;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "delivered_by")
    private Long deliveredBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (delivered == null) {
            delivered = false;
        }
        if (workStatus == null || workStatus.isBlank()) {
            workStatus = "PENDING";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
