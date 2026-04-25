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
@Table(name = "purchase_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code; // Código de orden

    @Column(name = "supplier_id")
    private Long supplierId; // Proveedor

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate; // Fecha de orden

    @Column(name = "status", nullable = false, length = 30)
    private String status; // CREADA, RECIBIDA, CANCELADA

    @Column(name = "total", precision = 15, scale = 2)
    private BigDecimal total; // Total de la orden

    @Column(name = "observations", length = 2000)
    private String observations; // Observaciones

    @Column(name = "reference_requests", length = 500)
    private String referenceRequests; // Referencias a solicitudes de materiales

    @Column(name = "cost_center_id")
    private Long costCenterId; // Centro de costo

    @Column(name = "created_by")
    private Long createdBy; // Usuario que creó

    @Column(name = "updated_by")
    private Long updatedBy; // Usuario que actualizó

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (orderDate == null) {
            orderDate = LocalDate.now();
        }
        if (status == null) {
            status = "CREADA";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
