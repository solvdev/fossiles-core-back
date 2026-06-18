package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_shipment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable cuando el envío pertenece solo a una OP (OPI/OPCK). */
    @Column(name = "distribution_id")
    private Long distributionId;

    /** OP manual (INTERNA / CLIENTE_KIOSKO) sin distribución. */
    @Column(name = "production_order_id")
    private Long productionOrderId;

    /** Liberación parcial LF que originó este envío. */
    @Column(name = "partial_release_id")
    private Long partialReleaseId;

    @Column(name = "shipment_number", nullable = false, unique = true, length = 50)
    private String shipmentNumber;

    /** Nullable: envío OPI solo constancia interna (sin kiosko). */
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "DRAFT"; // DRAFT, CONFIRMED, SENT, DELIVERED

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "packing_items", columnDefinition = "TEXT")
    private String packingItems;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "received_notes", columnDefinition = "TEXT")
    private String receivedNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distribution_id", insertable = false, updatable = false)
    private ProductDistributionEntity distribution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id", insertable = false, updatable = false)
    private ProductionOrderEntity productionOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private LocationEntity location;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "DRAFT";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

