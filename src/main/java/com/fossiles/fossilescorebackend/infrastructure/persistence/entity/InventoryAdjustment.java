package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_adjustment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAdjustment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_id")
    private Long locationId; // Nullable: null para materiales, requerido para productos

    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "color_id")
    private Long colorId; // Opcional: solo para productos con colores

    @Column(name = "system_sizes_data", columnDefinition = "TEXT")
    private String systemSizesData;

    @Column(name = "physical_sizes_data", columnDefinition = "TEXT")
    private String physicalSizesData;

    @Column(name = "system_stock", nullable = false, precision = 12, scale = 3)
    private BigDecimal systemStock;

    @Column(name = "physical_stock", nullable = false, precision = 12, scale = 3)
    private BigDecimal physicalStock;

    @Column(name = "adjustment_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal adjustmentQuantity; // physicalStock - systemStock

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDateTime adjustmentDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private LocationEntity location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private MaterialEntity material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_id", insertable = false, updatable = false)
    private ColorEntity color;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (adjustmentDate == null) {
            adjustmentDate = LocalDateTime.now();
        }
        // Calcular adjustmentQuantity automáticamente
        if (systemStock != null && physicalStock != null) {
            adjustmentQuantity = physicalStock.subtract(systemStock);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // Recalcular adjustmentQuantity si cambian los stocks
        if (systemStock != null && physicalStock != null) {
            adjustmentQuantity = physicalStock.subtract(systemStock);
        }
    }
}

