package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_variant_leather",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "color_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariantLeatherEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Null = aplica a todos los colores del producto */
    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "leather_material_id", nullable = false)
    private Long leatherMaterialId;

    @Column(name = "qty_per_unit", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal qtyPerUnit = BigDecimal.ONE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (qtyPerUnit == null) {
            qtyPerUnit = BigDecimal.ONE;
        }
    }
}
