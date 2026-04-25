package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_series", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"document_type", "series"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSeriesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType; // INVOICE, PURCHASE_ORDER, PRODUCTION_ORDER, QUOTE, etc.

    @Column(nullable = false, length = 20)
    private String series; // A, B, C, etc.

    @Column(name = "current_correlative", nullable = false)
    private Long currentCorrelative;

    @Column(length = 500)
    private String description;

    @Column(length = 20)
    private String status; // active, inactive

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
        if (status == null || status.isEmpty()) {
            status = "active";
        }
        if (currentCorrelative == null) {
            currentCorrelative = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

