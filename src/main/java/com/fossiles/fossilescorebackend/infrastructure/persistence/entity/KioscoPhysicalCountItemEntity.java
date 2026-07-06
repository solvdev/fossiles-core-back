package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Conteo fisico por producto/color dentro de una sesion: JSON ubicacion (V1..V7, E, BO) -> cantidad. */
@Entity
@Table(
        name = "kiosco_physical_count_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"count_id", "product_id", "color_id"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "count_id", nullable = false)
    private Long countId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    /** JSON ubicacion → cantidad; claves V1..V7, E, BO. */
    @Column(name = "counts_data", columnDefinition = "TEXT")
    private String countsData;

    /** JSON talla → cantidad fisica (cinchos); mismo patron que KioscoStockEntity.sizesData. */
    @Column(name = "size_counts_data", columnDefinition = "TEXT")
    private String sizeCountsData;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_id", insertable = false, updatable = false)
    private ColorEntity color;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
