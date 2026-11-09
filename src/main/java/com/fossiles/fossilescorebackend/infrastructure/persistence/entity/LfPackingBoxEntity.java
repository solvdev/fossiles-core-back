package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Caja de armado de Bodega PT para una OPV/OPC de Luis Felipe. Armar cajas es solo planeación: no mueve
 * inventario, ni numeración, ni precios. Al despachar se liga a la liberación parcial y al envío creados.
 */
@Entity
@Table(
        name = "lf_packing_box",
        uniqueConstraints = @UniqueConstraint(name = "uq_lf_packing_box_order_no", columnNames = {"production_order_id", "box_no"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LfPackingBoxEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "box_no", nullable = false)
    private Integer boxNo;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_OPEN;

    @Column(name = "partial_release_id")
    private Long partialReleaseId;

    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

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
        if (status == null || status.isBlank()) {
            status = STATUS_OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
