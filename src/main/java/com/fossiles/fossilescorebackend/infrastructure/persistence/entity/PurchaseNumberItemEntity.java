package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "purchase_number_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseNumberItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_number_id", nullable = false)
    private Long purchaseNumberId;

    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName; // Nombre del artículo

    @Column(name = "description", length = 1000)
    private String description; // Descripción detallada del artículo

    @Column(name = "supplier", nullable = false, length = 200)
    private String supplier; // Proveedor donde se comprará

    @Column(name = "estimated_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedPrice; // Precio estimado por unidad

    @Column(name = "quantity", nullable = false)
    private Integer quantity; // Cantidad a comprar

    @Column(name = "estimated_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedTotal; // estimatedPrice * quantity

    @Column(name = "actual_price", precision = 12, scale = 2)
    private BigDecimal actualPrice; // Precio real pagado (se llena cuando se crea el MinorExpense)

    @Column(name = "minor_expense_id")
    private Long minorExpenseId; // FK al MinorExpense cuando se registra el gasto real

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
        // Calcular estimatedTotal automáticamente
        if (estimatedPrice != null && quantity != null && estimatedTotal == null) {
            estimatedTotal = estimatedPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // Recalcular estimatedTotal si cambia precio o cantidad
        if (estimatedPrice != null && quantity != null) {
            estimatedTotal = estimatedPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}


