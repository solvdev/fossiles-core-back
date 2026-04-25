package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_receipt_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialReceiptItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_receipt_id", nullable = false)
    private Long materialReceiptId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "quantity_received", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityReceived;

    @Column(name = "unit_price_received", precision = 12, scale = 2)
    private BigDecimal unitPriceReceived;

    @Column(name = "supplier_id")
    private Long supplierId; // Proveedor asignado para este item

    @Column(name = "receipt_date")
    private LocalDate receiptDate; // Fecha específica de recepción para este item

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

