package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "purchase_number")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseNumberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_number", nullable = false, unique = true, length = 50)
    private String purchaseNumber; // Ej: "COMP-00001"

    @Column(name = "status", nullable = false, length = 30)
    private String status; // PENDIENTE=Abierta, TERMINADO=Cerrada, PAGADO=Pagada

    @Column(name = "description", length = 500)
    private String description; // Descripción opcional de la compra

    @Column(name = "total_amount", precision = 15, scale = 2)
    private java.math.BigDecimal totalAmount; // Cantidad total asignada a liquidar en esta compra

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDIENTE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

