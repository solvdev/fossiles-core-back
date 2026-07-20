package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "kiosco_internal_count_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"internal_count_id", "product_id", "color_id"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInternalCountItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "internal_count_id", nullable = false)
    private Long internalCountId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "counts_data", columnDefinition = "TEXT")
    private String countsData;

    @Column(name = "size_counts_data", columnDefinition = "TEXT")
    private String sizeCountsData;

    @Column(name = "size_location_counts_data", columnDefinition = "TEXT")
    private String sizeLocationCountsData;

    @Column(name = "hardware_location_counts_data", columnDefinition = "TEXT")
    private String hardwareLocationCountsData;

    @Column(name = "observation", columnDefinition = "TEXT")
    private String observation;

    @Column(name = "size_observations_data", columnDefinition = "TEXT")
    private String sizeObservationsData;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
