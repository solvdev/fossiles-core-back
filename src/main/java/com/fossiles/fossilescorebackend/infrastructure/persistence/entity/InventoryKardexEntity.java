package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_kardex")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryKardexEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "movement_type", nullable = false, length = 50)
    private String movementType; // ENTRY, EXIT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity; // Positiva para entrada, negativa para salida

    @Column(name = "quantity_before", precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after", precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "reference_type", length = 50)
    private String referenceType; // PURCHASE_ORDER, PRODUCTION_ORDER, TRANSFER, ADJUSTMENT

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber; // Ej: OC-00001

    @Column(length = 500)
    private String description;

    @Column(name = "movement_date", nullable = false)
    private LocalDateTime movementDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private MaterialEntity material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private LocationEntity location;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        if (movementDate == null) {
            movementDate = GuatemalaDateTime.now();
        }
    }
}

