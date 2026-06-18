package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "production_order_warehouse_unit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderWarehouseUnitEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "production_order_item_id", nullable = false)
    private Long productionOrderItemId;

    @Column(name = "unit_label", length = 200)
    private String unitLabel;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "size_key", length = 40, nullable = false)
    @Builder.Default
    private String sizeKey = "";

    @Column(name = "unit_seq", nullable = false)
    private Integer unitSeq;

    @Column(name = "receipt_status", length = 20, nullable = false)
    @Builder.Default
    private String receiptStatus = "PENDING";

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "shipment_ref_type", length = 40)
    private String shipmentRefType;

    @Column(name = "shipment_ref_id")
    private Long shipmentRefId;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "shipped_by")
    private Long shippedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (receiptStatus == null || receiptStatus.isBlank()) {
            receiptStatus = "PENDING";
        }
        if (sizeKey == null) {
            sizeKey = "";
        }
    }
}
