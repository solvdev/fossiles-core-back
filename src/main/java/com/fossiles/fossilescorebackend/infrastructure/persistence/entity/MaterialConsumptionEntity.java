package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_consumption")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialConsumptionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "bom_id")
    private Long bomId;

    @Column(name = "quantity_consumed", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityConsumed;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost;

    @Column(length = 20)
    @Builder.Default
    private String status = "CONSUMED";

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_by")
    private Long consumedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversed_by")
    private Long reversedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (consumedAt == null) consumedAt = LocalDateTime.now();
        if (status == null) status = "CONSUMED";
    }
}

