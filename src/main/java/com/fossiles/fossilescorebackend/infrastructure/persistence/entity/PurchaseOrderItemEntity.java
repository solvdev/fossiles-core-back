package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "purchase_order_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false)
    private Long purchaseOrderId; // FK a purchase_order

    @Column(name = "material_id")
    private Long materialId; // Material

    @Column(name = "quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity; // Cantidad

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice; // Precio unitario

    @Column(name = "subtotal", precision = 15, scale = 2)
    private BigDecimal subtotal; // Subtotal

    @Column(name = "supplier_id")
    private Long supplierId; // Proveedor del item

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Calcular subtotal si no está establecido
        if (subtotal == null && quantity != null && unitPrice != null) {
            subtotal = quantity.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

