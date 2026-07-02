package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "internal_shipment_request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "request_type", length = 30, nullable = false)
    private String requestType;

    @Column(name = "recipient_name", length = 200, nullable = false)
    private String recipientName;

    @Column(name = "recipient_phone", length = 50)
    private String recipientPhone;

    @Column(name = "recipient_tax_id", length = 50)
    private String recipientTaxId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "document_date", length = 10)
    private String documentDate;

    /** DEFECTOS: porcentaje del precio catálogo (ej. 50). PLANILLA: null (50 fijo al aprobar). */
    @Column(name = "discount_percent", precision = 5, scale = 2)
    private java.math.BigDecimal discountPercent;

    /** DEFECTOS: precio unitario fijo Q (excluyente con discount_percent). */
    @Column(name = "discount_amount", precision = 12, scale = 2)
    private java.math.BigDecimal discountAmount;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "product_shipment_id")
    private Long productShipmentId;

    /** OPI generada automáticamente cuando no hay stock PT/Devoluciones. */
    @Column(name = "production_order_id")
    private Long productionOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InternalShipmentRequestLineEntity> lines = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (status == null || status.isBlank()) {
            status = "PENDIENTE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
