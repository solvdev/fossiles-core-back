package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(length = 150)
    private String name;

    @Column(name = "category_id")
    private Long categoryId;

    /** DAMA, CABALLERO o UNISEX — línea de producto para POS y promociones. */
    @Column(name = "audience_category", length = 20)
    private String audienceCategory;

    /** CASUAL o REVERSIBLE — tipo de cincho FOSS; null si no aplica. */
    @Column(name = "cincho_type", length = 20)
    private String cinchoType;

    /** true = cincho de niño (solo cuando aplica cincho). */
    @Column(name = "cincho_for_kids")
    @Builder.Default
    private Boolean cinchoForKids = false;

    @Column(name = "prd_time", columnDefinition = "NUMERIC(10,2)")
    private Double prdTime;

    /** Unidades de este producto que caben en una tarea. Null = usar 2. */
    @Column(name = "units_per_task")
    private Integer unitsPerTask;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "discounted_price", precision = 12, scale = 2)
    private BigDecimal discountedPrice;

    @Column(name = "seller_price", precision = 12, scale = 2)
    private BigDecimal sellerPrice;

    @Column(length = 20)
    private String status;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Consumo de cuero por unidad en pies cuadrados (ft²) */
    @Column(name = "leather_consumption", precision = 10, scale = 3)
    private BigDecimal leatherConsumption;

    /** Indica si el producto requiere entrega de materiales de bodega. */
    @Column(name = "requires_materials")
    private Boolean requiresMaterials;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

