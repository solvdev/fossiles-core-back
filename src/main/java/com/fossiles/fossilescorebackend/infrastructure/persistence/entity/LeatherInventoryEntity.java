package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Inventario actual de cuero por material.
 * Se actualiza automáticamente con cada movimiento (recepción / entrega).
 */
@Entity
@Table(name = "leather_inventory", uniqueConstraints = {
    @UniqueConstraint(columnNames = "material_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeatherInventoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK al material (cuero) del catálogo */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** Cantidad actual disponible en pies cuadrados */
    @Builder.Default
    @Column(name = "quantity_available", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityAvailable = BigDecimal.ZERO;

    /** Total recibido históricamente */
    @Builder.Default
    @Column(name = "total_received", nullable = false, precision = 12, scale = 3)
    private BigDecimal totalReceived = BigDecimal.ZERO;

    /** Total entregado a producción históricamente */
    @Builder.Default
    @Column(name = "total_delivered", nullable = false, precision = 12, scale = 3)
    private BigDecimal totalDelivered = BigDecimal.ZERO;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private MaterialEntity material;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (quantityAvailable == null) quantityAvailable = BigDecimal.ZERO;
        if (totalReceived == null) totalReceived = BigDecimal.ZERO;
        if (totalDelivered == null) totalDelivered = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

